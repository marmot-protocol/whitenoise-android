package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val ATTACHMENT_PREFERENCES_NAME = "whitenoise"

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
)

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

    fun encode(request: AttachmentTransferRequest): Data =
        workDataOf(
            KEY_ACCOUNT_REF to request.accountRef,
            KEY_GROUP_ID_HEX to request.groupIdHex,
            KEY_MESSAGE_ID_HEX to request.messageIdHex,
            KEY_ATTACHMENT_INDEX to request.attachmentIndex,
        )

    fun decode(data: Data): AttachmentTransferRequest? {
        val accountRef = data.getString(KEY_ACCOUNT_REF).orEmpty()
        val groupIdHex = data.getString(KEY_GROUP_ID_HEX).orEmpty()
        val messageIdHex = data.getString(KEY_MESSAGE_ID_HEX).orEmpty()
        val attachmentIndex = data.getInt(KEY_ATTACHMENT_INDEX, -1)
        val valid =
            accountRef.isNotBlank() &&
                HEX_ID.matches(groupIdHex) &&
                HEX_ID.matches(messageIdHex) &&
                attachmentIndex >= 0
        return if (valid) {
            AttachmentTransferRequest(accountRef, groupIdHex, messageIdHex, attachmentIndex)
        } else {
            null
        }
    }

    private val HEX_ID = Regex("^[0-9a-fA-F]{64}$")
}

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

internal fun attachmentIdentityDigest(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

internal fun attachmentAutomaticAccountTag(accountRef: String): String {
    val identity = attachmentIdentityDigest(accountRef)
    return "attachment_download_auto_account_$identity"
}

internal fun attachmentIdentityTag(request: AttachmentTransferRequest): String =
    "attachment_download_identity_${attachmentIdentityDigest(attachmentDownloadWorkName(request))}"

internal fun shouldRetryAttachmentDownloadWork(
    runAttemptCount: Int,
    failure: Throwable,
): Boolean =
    runAttemptCount < AttachmentDownloadWorker.MAX_RETRY_ATTEMPTS &&
        (failure is AttachmentReferenceNotReadyException || isTransientAttachmentDownloadFailure(failure))

internal fun shouldCancelQueuedAutomaticWork(
    state: WorkInfo.State,
    hasInteractiveIntent: Boolean,
): Boolean =
    state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) &&
        !hasInteractiveIntent

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

    override suspend fun doWork(): Result {
        val request = AttachmentDownloadWorkData.decode(inputData)
        val application = applicationContext as? WhiteNoiseApplication
        return if (request == null || application == null) {
            Result.failure()
        } else {
            val intentStore = attachmentIntentStore(applicationContext)
            val priority = intentStore.priorityFor(request)
            if (
                priority == AttachmentDownloadPriority.Automatic &&
                intentStore.isAutomaticPaused(request.accountRef)
            ) {
                Result.success()
            } else {
                performDownload(application, request, priority, intentStore)
            }
        }
    }

    private suspend fun performDownload(
        application: WhiteNoiseApplication,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        intentStore: AttachmentDownloadIntentStore,
    ): Result =
        try {
            if (!durableDownload(application, request, priority)) {
                throw java.io.IOException("attachment did not reach encrypted cache")
            }
            intentStore.setInteractive(request, interactive = false)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (expectedFailure: Throwable) {
            Log.w(
                TAG,
                "durable attachment download failed " +
                    "msg=${request.messageIdHex.take(LOG_ID_PREFIX_LENGTH)}#${request.attachmentIndex}",
                expectedFailure,
            )
            if (shouldRetryAttachmentDownloadWork(runAttemptCount, expectedFailure)) {
                Result.retry()
            } else {
                // A terminal worker must not leave an identity permanently
                // immune to a later automatic-backlog stop. The durable open
                // intent remains; returning to the bubble can explicitly
                // promote and retry it again.
                intentStore.setInteractive(request, interactive = false)
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
        return application.appState.downloadAttachmentForDurableWork(request, priority)
    }

    companion object {
        internal const val MAX_RETRY_ATTEMPTS = 1
        private const val TAG = "DMAttachmentWorker"
        private const val BACKOFF_SECONDS = 30L
        private const val LOG_ID_PREFIX_LENGTH = 8

        internal fun enqueue(
            context: Context,
            request: AttachmentTransferRequest,
            priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
        ) {
            val intentStore = attachmentIntentStore(context.applicationContext)
            if (
                priority == AttachmentDownloadPriority.Automatic &&
                intentStore.isAutomaticPaused(request.accountRef)
            ) {
                return
            }
            if (priority == AttachmentDownloadPriority.Interactive) {
                intentStore.setInteractive(request, interactive = true)
            }
            val work =
                OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                    .setInputData(AttachmentDownloadWorkData.encode(request))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresStorageNotLow(true)
                            .build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(attachmentIdentityTag(request))
                    .apply {
                        if (priority == AttachmentDownloadPriority.Automatic) {
                            addTag(attachmentAutomaticAccountTag(request.accountRef))
                        }
                    }.build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    attachmentDownloadWorkName(request),
                    ExistingWorkPolicy.KEEP,
                    work,
                )
            }.onFailure { Log.w(TAG, "failed to enqueue durable attachment download", it) }
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

private fun attachmentIntentStore(context: Context): AttachmentDownloadIntentStore =
    AttachmentDownloadIntentStore(
        context.getSharedPreferences(ATTACHMENT_PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

private fun AttachmentDownloadIntentStore.priorityFor(request: AttachmentTransferRequest): AttachmentDownloadPriority =
    if (isInteractive(request)) {
        AttachmentDownloadPriority.Interactive
    } else {
        AttachmentDownloadPriority.Automatic
    }

private const val BYTE_MASK = 0xFF
