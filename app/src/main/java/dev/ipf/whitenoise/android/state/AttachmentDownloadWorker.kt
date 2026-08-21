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

internal fun AttachmentTransferRequest.cacheKey(): String = mediaCacheKey(accountRef, groupIdHex, messageIdHex, attachmentIndex)

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

internal fun attachmentAutomaticAccountTag(accountRef: String): String = "attachment_download_auto_account_${attachmentIdentityDigest(accountRef)}"

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

/**
 * Durable safety net for document downloads. Foreground UI callers still join
 * the same app-level in-flight Deferred for immediate response; this worker
 * makes the intent survive process death and verifies that the result reached
 * the encrypted disk cache before declaring success.
 */
class AttachmentDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val request = AttachmentDownloadWorkData.decode(inputData)
        val application = applicationContext as? WhiteNoiseApplication
        if (request == null || application == null) return Result.failure()
        val intentStore =
            AttachmentDownloadIntentStore(
                applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        val priority =
            if (intentStore.isInteractive(request)) {
                AttachmentDownloadPriority.Interactive
            } else {
                AttachmentDownloadPriority.Automatic
            }
        if (priority == AttachmentDownloadPriority.Automatic && intentStore.isAutomaticPaused(request.accountRef)) {
            return Result.success()
        }
        return try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            if (!application.appState.downloadAttachmentForDurableWork(request, priority)) {
                throw java.io.IOException("attachment did not reach encrypted cache")
            }
            intentStore.clearInteractive(request)
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
                intentStore.clearInteractive(request)
                Result.failure()
            }
        }
    }

    companion object {
        internal const val MAX_RETRY_ATTEMPTS = 1
        private const val TAG = "DMAttachmentWorker"
        private const val BACKOFF_SECONDS = 30L
        private const val LOG_ID_PREFIX_LENGTH = 8
        private const val PREFERENCES_NAME = "whitenoise"

        internal fun enqueue(
            context: Context,
            request: AttachmentTransferRequest,
            priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
        ) {
            val intentStore =
                AttachmentDownloadIntentStore(
                    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
                )
            if (priority == AttachmentDownloadPriority.Automatic && intentStore.isAutomaticPaused(request.accountRef)) return
            if (priority == AttachmentDownloadPriority.Interactive) intentStore.markInteractive(request)
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
            val intentStore =
                AttachmentDownloadIntentStore(
                    appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
                )
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

private const val BYTE_MASK = 0xFF
