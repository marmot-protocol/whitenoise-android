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
        val accountRef = data.getString(KEY_ACCOUNT_REF)?.takeIf { it.isNotBlank() } ?: return null
        val groupIdHex = data.getString(KEY_GROUP_ID_HEX)?.takeIf(HEX_ID::matches) ?: return null
        val messageIdHex = data.getString(KEY_MESSAGE_ID_HEX)?.takeIf(HEX_ID::matches) ?: return null
        val attachmentIndex = data.getInt(KEY_ATTACHMENT_INDEX, -1).takeIf { it >= 0 } ?: return null
        return AttachmentTransferRequest(accountRef, groupIdHex, messageIdHex, attachmentIndex)
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
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    return "attachment_download_" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun shouldRetryAttachmentDownloadWork(
    runAttemptCount: Int,
    failure: Throwable,
): Boolean =
    runAttemptCount < AttachmentDownloadWorker.MAX_RETRY_ATTEMPTS &&
        (failure is AttachmentReferenceNotReadyException || isTransientAttachmentDownloadFailure(failure))

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
        val request = AttachmentDownloadWorkData.decode(inputData) ?: return Result.failure()
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.failure()
        return try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            if (!application.appState.downloadAttachmentForDurableWork(request)) {
                throw java.io.IOException("attachment did not reach encrypted cache")
            }
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "durable attachment download failed msg=${request.messageIdHex.take(8)}#${request.attachmentIndex}",
                failure,
            )
            if (shouldRetryAttachmentDownloadWork(runAttemptCount, failure)) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val MAX_RETRY_ATTEMPTS = 1
        private const val TAG = "DMAttachmentWorker"
        private const val BACKOFF_SECONDS = 30L

        internal fun enqueue(
            context: Context,
            request: AttachmentTransferRequest,
        ) {
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
                    .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    attachmentDownloadWorkName(request),
                    ExistingWorkPolicy.KEEP,
                    work,
                )
            }.onFailure { Log.w(TAG, "failed to enqueue durable attachment download", it) }
        }
    }
}
