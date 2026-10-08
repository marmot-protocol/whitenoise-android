package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val ATTACHMENT_PREFERENCES_NAME = "whitenoise"
internal val ATTACHMENT_GROUP_ID_HEX = Regex("^[0-9a-fA-F]{32}$")
internal val ATTACHMENT_MESSAGE_ID_HEX = Regex("^[0-9a-fA-F]{64}$")

/**
 * Minimal identity needed to find an attachment again from MDK after process
 * death. The media reference itself deliberately stays in MDK's SQLite source
 * of truth: signed locators, hashes, nonces, filenames and captions never enter
 * WorkManager's plaintext database.
 */
internal data class AttachmentTransferRequest(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val attachmentIndex: Int,
    val sourceMessageIdHex: String? = null,
)

/** Returns the exact native target when the authoritative source id is known. */
internal fun AttachmentTransferRequest.nativeTarget(): NativeAttachmentTarget? =
    sourceMessageIdHex?.let { sourceId ->
        NativeAttachmentTarget(messageIdHex, sourceId, attachmentIndex)
    }

/** Returns the encrypted-cache key for this protocol-owned attachment identity. */
internal fun AttachmentTransferRequest.cacheKey(): String =
    mediaCacheKey(
        accountRef,
        groupIdHex,
        messageIdHex,
        attachmentIndex,
    )

internal object AttachmentDownloadWorkData {
    private const val KEY_ACCOUNT_REF = "account_ref"
    private const val KEY_GROUP_ID_HEX = "group_id_hex"
    private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
    private const val KEY_ATTACHMENT_INDEX = "attachment_index"
    private const val KEY_SOURCE_MESSAGE_ID_HEX = "source_message_id_hex"
    private const val KEY_INTERRUPTION_BACKOFF = "interruption_backoff"
    private const val KEY_RETIRED = "retired"

    /**
     * Encodes only the minimal identity needed for MDK to resolve the attachment again.
     * [interruptionBackoff] marks a spec built with the system-interruption backoff
     * opt-in, so a spec persisted before that opt-in existed is recognizable by its absence.
     */
    fun encode(
        request: AttachmentTransferRequest,
        interruptionBackoff: Boolean = false,
        retired: Boolean = false,
    ): Data {
        val entries =
            mutableListOf<Pair<String, Any?>>(
                KEY_ACCOUNT_REF to request.accountRef,
                KEY_GROUP_ID_HEX to request.groupIdHex,
                KEY_MESSAGE_ID_HEX to request.messageIdHex,
                KEY_ATTACHMENT_INDEX to request.attachmentIndex,
                KEY_SOURCE_MESSAGE_ID_HEX to request.sourceMessageIdHex,
            )
        if (interruptionBackoff) entries += KEY_INTERRUPTION_BACKOFF to true
        if (retired) entries += KEY_RETIRED to true
        return workDataOf(*entries.toTypedArray())
    }

    /** True for the inert spec that [AttachmentDownloadWorker.retireWork] leaves in place of cancelled work. */
    fun isRetired(data: Data): Boolean = data.getBoolean(KEY_RETIRED, false)

    /** True when the persisted spec was built with the system-interruption backoff opt-in. */
    fun hasInterruptionBackoff(data: Data): Boolean = data.getBoolean(KEY_INTERRUPTION_BACKOFF, false)

    /** Rejects malformed WorkManager input before it can reach MDK or cache paths. */
    fun decode(data: Data): AttachmentTransferRequest? {
        val accountRef = data.getString(KEY_ACCOUNT_REF).orEmpty()
        val groupIdHex = data.getString(KEY_GROUP_ID_HEX).orEmpty()
        val messageIdHex = data.getString(KEY_MESSAGE_ID_HEX).orEmpty()
        val attachmentIndex = data.getInt(KEY_ATTACHMENT_INDEX, -1)
        val sourceMessageIdHex = data.getString(KEY_SOURCE_MESSAGE_ID_HEX)
        val valid =
            accountRef.isNotBlank() &&
                ATTACHMENT_GROUP_ID_HEX.matches(groupIdHex) &&
                ATTACHMENT_MESSAGE_ID_HEX.matches(messageIdHex) &&
                (sourceMessageIdHex == null || ATTACHMENT_MESSAGE_ID_HEX.matches(sourceMessageIdHex)) &&
                attachmentIndex >= 0
        return if (valid) {
            AttachmentTransferRequest(accountRef, groupIdHex, messageIdHex, attachmentIndex, sourceMessageIdHex)
        } else {
            null
        }
    }
}

/** Returns the unique WorkManager name used to coalesce this attachment transfer. */
internal fun attachmentDownloadWorkName(request: AttachmentTransferRequest): String {
    val canonical =
        listOf(
            request.accountRef,
            request.groupIdHex.lowercase(),
            request.messageIdHex.lowercase(),
            request.attachmentIndex.toString(),
        ).joinToString("\u0000")
    return "attachment_download_${attachmentIdentityDigest(canonical)}"
}

/** Produces a stable lowercase digest without persisting the source identity. */
internal fun attachmentIdentityDigest(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

/** Returns the WorkManager tag used to pause one account's automatic backlog. */
internal fun attachmentAutomaticAccountTag(accountRef: String): String {
    val identity = attachmentIdentityDigest(accountRef)
    return "attachment_download_auto_account_$identity"
}

/** Returns the WorkManager tag shared by automatic and interactive instances. */
internal fun attachmentIdentityTag(request: AttachmentTransferRequest): String =
    "attachment_download_identity_${attachmentIdentityDigest(attachmentDownloadWorkName(request))}"

/**
 * Allows one follow-up after a completed transient failure. The budget is the persisted
 * [transientRetrySpent] flag, not WorkManager's run-attempt count, because every platform
 * interruption also starts a new run and would otherwise spend the failure budget.
 */
internal fun shouldRetryAttachmentDownloadWork(
    transientRetrySpent: Boolean,
    failure: Throwable,
): Boolean =
    !transientRetrySpent &&
        (failure is AttachmentReferenceNotReadyException || isTransientAttachmentDownloadFailure(failure))

internal fun shouldCancelQueuedAutomaticWork(
    state: WorkInfo.State,
    hasInteractiveIntent: Boolean,
): Boolean =
    state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) &&
        !hasInteractiveIntent

internal enum class AttachmentDownloadWorkState {
    Active,
    Finished,
}

/**
 * Observes the durable transfer independently of a conversation controller.
 * The interactive preference bridges enqueue registration, when WorkManager's
 * first snapshot can still be empty or contain only an older generation.
 */
internal fun attachmentDownloadWorkState(
    context: Context,
    request: AttachmentTransferRequest,
    hasInteractiveIntent: () -> Boolean,
): Flow<AttachmentDownloadWorkState> =
    WorkManager
        .getInstance(context.applicationContext)
        .getWorkInfosForUniqueWorkFlow(attachmentDownloadWorkName(request))
        .combine(AttachmentDownloadJobEvents.revision) { infos, _ ->
            if (hasInteractiveIntent() || infos.any { !it.state.isFinished }) {
                AttachmentDownloadWorkState.Active
            } else {
                AttachmentDownloadWorkState.Finished
            }
        }.distinctUntilChanged()

internal class AttachmentReferenceNotReadyException : IllegalStateException("attachment reference is not projected yet")

internal typealias PerformDurableAttachmentDownload = suspend (
    WhiteNoiseApplication,
    AttachmentTransferRequest,
    AttachmentDownloadPriority,
) -> Boolean

/**
 * Durable safety net for document downloads. Foreground UI callers still join
 * the same app-level in-flight Deferred for immediate response; this worker
 * makes the intent survive process death and verifies that the result reached
 * the encrypted disk cache before declaring success.
 */
class AttachmentDownloadWorker : CoroutineWorker {
    private val performDownloadOverride: PerformDurableAttachmentDownload?

    constructor(
        appContext: Context,
        params: WorkerParameters,
    ) : this(appContext, params, null)

    internal constructor(
        appContext: Context,
        params: WorkerParameters,
        performDownloadOverride: PerformDurableAttachmentDownload?,
    ) : super(appContext, params) {
        this.performDownloadOverride = performDownloadOverride
    }

    /** Resumes a durable download, promoting explicit work to a typed foreground service. */
    override suspend fun doWork(): Result {
        val request = AttachmentDownloadWorkData.decode(inputData)
        val application = applicationContext as? WhiteNoiseApplication
        return if (request == null || application == null) {
            Result.failure()
        } else if (AttachmentDownloadWorkData.isRetired(inputData)) {
            Result.success()
        } else {
            val intentStore = attachmentIntentStore(applicationContext)
            val priority = intentStore.priorityFor(request)
            if (
                priority == AttachmentDownloadPriority.Automatic &&
                (intentStore.isAutomaticPaused(request.accountRef) || intentStore.isAutomaticSuppressed(request))
            ) {
                intentStore.resetTransientRetry(request)
                Result.success()
            } else {
                val ordinary = priority == AttachmentDownloadPriority.Automatic
                if (ordinary && !AttachmentDownloadWorkData.hasInterruptionBackoff(inputData)) {
                    adoptInterruptionBackoff(request)
                }
                val foreground =
                    attachmentExecutionClass(priority, userVisible = false, Build.VERSION.SDK_INT) ==
                        AttachmentExecutionClass.ForegroundWork
                runTransfer(application, request, priority, intentStore, foreground)
            }
        }
    }

    /**
     * Runs one transfer, counting it in the ledger while its card is live when it runs in the [foreground].
     *
     * The ledger entry always ends with this run's outcome, including when the scheduler cancels the run, and
     * carries only the owner and the attempt, so a repeated card can be matched to retry churn against a
     * user-initiated job on the same attachment without naming the attachment.
     */
    private suspend fun runTransfer(
        application: WhiteNoiseApplication,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        intentStore: AttachmentDownloadIntentStore,
        foreground: Boolean,
    ): Result {
        val ledger = AttachmentTransfers.ledger
        val token =
            if (foreground) {
                ledger.begin(attachmentJobId(request), AttachmentTransferOwner.WorkManager, runAttemptCount)
            } else {
                null
            }
        var outcome = AttachmentTransferOutcome.Stopped
        try {
            if (foreground) {
                try {
                    setForeground(attachmentWorkForegroundInfo(applicationContext, request))
                } catch (failure: IllegalStateException) {
                    // A background retry may be denied foreground-service startup.
                    // WorkManager can still run this durable request as ordinary work.
                    Log.w(TAG, "attachment_foreground_unavailable type=${failure.javaClass.simpleName}")
                }
            }
            return performDownload(application, request, priority, intentStore).also { outcome = outcomeOf(it) }
        } finally {
            token?.let { ledger.end(it, outcome) }
        }
    }

    /** Maps a worker result to how the run ended, so a retry is not reported as a failure. */
    private fun outcomeOf(result: Result): AttachmentTransferOutcome =
        when (result) {
            is Result.Success -> AttachmentTransferOutcome.Completed
            is Result.Retry -> AttachmentTransferOutcome.Retrying
            else -> AttachmentTransferOutcome.Failed
        }

    /** Keeps scheduler interruption distinct from terminal acquisition so only live completed runs retire intent. */
    private suspend fun performDownload(
        application: WhiteNoiseApplication,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        intentStore: AttachmentDownloadIntentStore,
    ): Result =
        try {
            if (!durableDownload(application, request, priority)) {
                Log.w(TAG, "durable_attachment_download_not_retained")
                intentStore.setInteractive(request, interactive = false)
                intentStore.resetTransientRetry(request)
                Result.failure()
            } else {
                intentStore.setInteractive(request, interactive = false)
                intentStore.resetTransientRetry(request)
                Result.success()
            }
        } catch (cancel: CancellationException) {
            // A cancelled shared fetch does not mean WorkManager stopped this
            // worker. Finish that transfer so its foreground notification and
            // interactive intent cannot linger. Scheduler cancellation still
            // propagates and retains intent for the next run.
            if (!currentCoroutineContext().isActive) {
                val reason =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason.toString() else "unavailable"
                Log.w(TAG, "attachment_work_stopped reason=$reason run_attempt=$runAttemptCount")
                throw cancel
            }
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "attachment_fetch_cancelled type=${cancel.javaClass.simpleName}")
            intentStore.setInteractive(request, interactive = false)
            intentStore.resetTransientRetry(request)
            Result.failure()
        } catch (expectedFailure: Throwable) {
            // Exception class is safe to log; attachment identity, URLs and
            // decrypted metadata are deliberately excluded from logcat.
            Log.w(TAG, "durable_attachment_download_failed type=${expectedFailure.javaClass.simpleName}")
            if (shouldRetryAttachmentDownloadWork(intentStore.hasSpentTransientRetry(request), expectedFailure)) {
                intentStore.spendTransientRetry(request)
                Result.retry()
            } else {
                // A terminal worker must not leave an identity permanently
                // immune to a later automatic-backlog stop. The durable open
                // intent remains; returning to the bubble can explicitly
                // promote and retry it again.
                intentStore.setInteractive(request, interactive = false)
                intentStore.resetTransientRetry(request)
                Result.failure()
            }
        }

    private suspend fun durableDownload(
        application: WhiteNoiseApplication,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
    ): Boolean {
        val override = performDownloadOverride
        if (override != null) {
            return override(application, request, priority)
        }
        withContext(Dispatchers.Main.immediate) {
            application.appState.ensureNotificationRuntimeStarted()
        }
        return application.appState.downloadAttachmentForDurableWork(
            request = request,
            priority = priority,
        )
    }

    /**
     * Upgrades a spec persisted before the interruption backoff existed. It is updated in place by
     * id, so it can never recreate work that was cancelled meanwhile, and a running spec takes the
     * change for its next run. The first stop of the current run still follows the old behavior.
     */
    private fun adoptInterruptionBackoff(request: AttachmentTransferRequest) {
        runCatching {
            WorkManager
                .getInstance(applicationContext)
                .updateWork(buildRequest(request, AttachmentDownloadPriority.Automatic, id))
        }.onFailure { Log.w(TAG, "attachment_backoff_adoption_failed") }
    }

    companion object {
        private const val TAG = "DMAttachmentWorker"
        internal const val BACKOFF_SECONDS = 30L
        private const val EXPLICIT_LOOKUP_RETRIES = 1
        internal const val RETIRED_TAG = "attachment_download_retired"
        private val explicitFence = ExplicitEnqueueFence()

        /**
         * Builds the durable request for one transfer. Ordinary (automatic) work opts into WorkManager's
         * backoff for system interruptions: a job stopped by Android resumes after an exponential delay
         * from [BACKOFF_SECONDS], doubling per run up to WorkManager's five-hour
         * ceiling, instead of restarting at once. Explicit work does not opt in, so a stopped explicit
         * download resumes immediately. [id] updates an existing spec in place.
         */
        internal fun buildRequest(
            request: AttachmentTransferRequest,
            priority: AttachmentDownloadPriority,
            id: UUID? = null,
        ): OneTimeWorkRequest {
            val ordinary = priority == AttachmentDownloadPriority.Automatic
            return OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                .setInputData(AttachmentDownloadWorkData.encode(request, interruptionBackoff = ordinary))
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(attachmentIdentityTag(request))
                .apply {
                    if (id != null) setId(id)
                    if (ordinary) {
                        addTag(attachmentAutomaticAccountTag(request.accountRef))
                        setBackoffForSystemInterruptions()
                    }
                }.build()
        }

        /**
         * Retires the transfer's WorkManager work by replacing it with an inert spec rather than
         * cancelling it. WorkManager 2.12.0 reschedules a cancelled worker that opted into interruption
         * backoff back to ENQUEUED (upstream issue 552283281), which would resurrect a download that was
         * just handed to a user-initiated job or cancelled. Replacing deletes the old row, so there is
         * nothing to resurrect, and the inert spec succeeds without doing any work.
         */
        internal fun retireWork(
            context: Context,
            request: AttachmentTransferRequest,
        ) {
            runCatching {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        attachmentDownloadWorkName(request),
                        ExistingWorkPolicy.REPLACE,
                        buildRetirementRequest(request),
                    )
            }.onFailure { Log.w(TAG, "attachment_download_retire_failed") }
        }

        /**
         * Builds the inert spec that replaces retired work. It carries [RETIRED_TAG] so a fresh explicit request
         * recognizes it as replaceable, and [constraints] exist only so tests can hold it pending.
         */
        internal fun buildRetirementRequest(
            request: AttachmentTransferRequest,
            constraints: Constraints = Constraints.NONE,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                .setInputData(AttachmentDownloadWorkData.encode(request, retired = true))
                .setConstraints(constraints)
                .addTag(attachmentIdentityTag(request))
                .addTag(RETIRED_TAG)
                .build()

        /**
         * Persists one transfer as unique WorkManager work. Automatic work coalesces onto any existing
         * work and backs off after platform interruptions. An explicit request replaces waiting
         * automatic work instead, and on Android 14 or later a visible one runs as a user-initiated job.
         */
        internal fun enqueue(
            context: Context,
            request: AttachmentTransferRequest,
            priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
            userVisible: Boolean = false,
        ) {
            val intentStore = attachmentIntentStore(context.applicationContext)
            if (
                priority == AttachmentDownloadPriority.Automatic &&
                (intentStore.isAutomaticPaused(request.accountRef) || intentStore.isAutomaticSuppressed(request))
            ) {
                return
            }
            if (priority == AttachmentDownloadPriority.Interactive) {
                intentStore.restoreAutomatic(request)
                intentStore.setInteractive(request, interactive = true)
                if (
                    attachmentExecutionClass(priority, userVisible, Build.VERSION.SDK_INT) ==
                    AttachmentExecutionClass.UserInitiatedJob &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    AttachmentUserInitiatedDownloads.schedule(context.applicationContext, request)
                ) {
                    retireWork(context.applicationContext, request)
                    return
                }
            }
            val work = buildRequest(request, priority)
            if (priority == AttachmentDownloadPriority.Interactive) {
                enqueueExplicit(context.applicationContext, request, work, intentStore)
            } else {
                runCatching {
                    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                        attachmentDownloadWorkName(request),
                        ExistingWorkPolicy.KEEP,
                        work,
                    )
                }.onFailure { Log.w(TAG, "attachment_download_enqueue_failed") }
            }
        }

        /**
         * Queues an explicit request. Automatic work for the same identity may be backed off for
         * hours or still running unpromoted, and `KEEP` would strand the request behind it, so live
         * automatic work is replaced by a fresh explicit spec. Explicit work already queued or
         * running stays, preserving coalescing. The probe is asynchronous, and the intent is
         * re-checked afterwards so a cancel that raced it is not undone. A failed lookup is retried
         * once. If it fails again nothing is enqueued and the interactive intent stays set, because
         * choosing a policy without knowing what exists could strand the request behind backed-off work.
         */
        internal fun enqueueExplicit(
            context: Context,
            request: AttachmentTransferRequest,
            work: OneTimeWorkRequest,
            intentStore: AttachmentDownloadIntentStore,
            lookup: (WorkManager, String) -> ListenableFuture<List<WorkInfo>> = { manager, name ->
                manager.getWorkInfosForUniqueWork(name)
            },
            attempt: Int = 0,
            fence: ExplicitEnqueueFence = explicitFence,
            lease: ExplicitEnqueueFence.Lease? = null,
        ) {
            val name = attachmentDownloadWorkName(request)
            val held = (if (attempt == 0) fence.tryAcquire(name) else lease) ?: return
            runCatching {
                val manager = WorkManager.getInstance(context)
                val probe = lookup(manager, name)
                probe.addListener(
                    {
                        val outcome = runCatching { probe.get() }
                        val infos = outcome.getOrNull()
                        when {
                            infos != null ->
                                fence.runIfCurrent(held) {
                                    settleExplicit(manager, name, request, work, intentStore, infos) {
                                        fence.release(held)
                                    }
                                }
                            attempt < EXPLICIT_LOOKUP_RETRIES ->
                                if (fence.isCurrent(held)) {
                                    val next = attempt + 1
                                    enqueueExplicit(context, request, work, intentStore, lookup, next, fence, held)
                                }
                            else -> {
                                val type = outcome.exceptionOrNull()?.javaClass?.simpleName
                                Log.w(TAG, "attachment_download_lookup_failed type=$type")
                                fence.release(held)
                            }
                        }
                    },
                    Executor { task -> task.run() },
                )
            }.onFailure {
                Log.w(TAG, "attachment_download_enqueue_failed")
                fence.release(held)
            }
        }

        /**
         * Replaces live automatic work, or the inert spec a retirement leaves, with the explicit spec, or keeps
         * what exists, unless the tap was cancelled. [onSettled] runs once the enqueue has completed or was skipped,
         * so the fence covers the whole decision.
         */
        private fun settleExplicit(
            manager: WorkManager,
            name: String,
            request: AttachmentTransferRequest,
            work: OneTimeWorkRequest,
            intentStore: AttachmentDownloadIntentStore,
            infos: List<WorkInfo>,
            onSettled: () -> Unit,
        ) {
            val automaticTag = attachmentAutomaticAccountTag(request.accountRef)
            val replaceable =
                infos.any { info -> !info.state.isFinished && (automaticTag in info.tags || RETIRED_TAG in info.tags) }
            val operation =
                if (intentStore.isInteractive(request)) {
                    val policy = if (replaceable) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                    runCatching { manager.enqueueUniqueWork(name, policy, work) }
                        .onFailure { Log.w(TAG, "attachment_download_enqueue_failed") }
                        .getOrNull()
                } else {
                    null
                }
            if (operation == null) {
                onSettled()
            } else {
                operation.result.addListener({ onSettled() }, Executor { task -> task.run() })
            }
        }

        /**
         * Revokes one attachment's durable transfer.
         *
         * The interactive intent is cleared before the unique work is cancelled
         * so a retry scheduled between the two steps cannot re-arm the identity,
         * and so process restoration cannot resurrect a cancelled transfer.
         */
        internal fun cancelForRequest(
            context: Context,
            request: AttachmentTransferRequest,
        ) {
            val appContext = context.applicationContext
            attachmentIntentStore(appContext).apply {
                suppressAutomatic(request)
                setInteractive(request, interactive = false)
                resetTransientRetry(request)
            }
            retireWork(appContext, request)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                AttachmentUserInitiatedDownloads.cancel(appContext, request)
            }
        }

        internal suspend fun cancelQueuedAutomatic(
            context: Context,
            accountRef: String,
        ): Int {
            val appContext = context.applicationContext
            val manager = WorkManager.getInstance(appContext)
            val intentStore = attachmentIntentStore(appContext)
            val queued =
                withContext(Dispatchers.IO) {
                    manager.getWorkInfosByTag(attachmentAutomaticAccountTag(accountRef)).get()
                }.filter { info ->
                    shouldCancelQueuedAutomaticWork(
                        state = info.state,
                        hasInteractiveIntent = intentStore.containsInteractiveTag(info.tags),
                    )
                }
            queued.forEach { manager.cancelWorkById(it.id) }
            return queued.size
        }
    }
}

internal fun attachmentIntentStore(context: Context): AttachmentDownloadIntentStore =
    AttachmentDownloadIntentStore(
        context.getSharedPreferences(ATTACHMENT_PREFERENCES_NAME, Context.MODE_PRIVATE),
        EncryptedAttachmentInstallerHandoffRecordStore.create(context),
    )

private fun AttachmentDownloadIntentStore.priorityFor(request: AttachmentTransferRequest): AttachmentDownloadPriority =
    if (isInteractive(request)) {
        AttachmentDownloadPriority.Interactive
    } else {
        AttachmentDownloadPriority.Automatic
    }

private const val BYTE_MASK = 0xFF
