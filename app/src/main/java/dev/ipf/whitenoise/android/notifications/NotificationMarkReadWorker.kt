package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NotificationMarkReadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val retryStore = NotificationActionRetryStore.create(applicationContext)
        val retryKey = id.toString()
        val action =
            NotificationActionWorkData
                .decode(inputData)
                ?.takeIf { it.kind == NotificationActionKind.MARK_READ }
                ?: return Result.failure().also { retryStore.clear(retryKey) }
        if (!application.appState.notificationActionsAllowed) {
            Log.w(TAG, "mark-read deferred by app lock group=${action.target.groupIdHex.take(8)}")
            if (retryStore.shouldDeferForLock(retryKey, NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS)) {
                return Result.retry()
            }
            Log.w(TAG, "mark-read lock wait expired group=${action.target.groupIdHex.take(8)}")
            return Result.failure().also { retryStore.clear(retryKey) }
        }
        if (retryStore.operationFailureCount(retryKey) >= MAX_ATTEMPTS) {
            return Result.failure().also { retryStore.clear(retryKey) }
        }

        return try {
            // Capture the baseline before the round-trip so a newer message,
            // reaction, or mention arriving meanwhile keeps its notification.
            val dismissBaselineMs = System.currentTimeMillis()
            val markedRead =
                withContext(Dispatchers.Main.immediate) {
                    application.appState.ensureNotificationRuntimeStarted()
                    application.appState.markNotificationMessageRead(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        messageIdHex = action.target.messageIdHex.orEmpty(),
                    )
                }
            if (!markedRead) return markReadFailureResult(retryStore, retryKey)

            LocalNotificationPresenter(applicationContext).dismissActionNotificationAndOlderSiblings(
                notificationTag = action.notificationTag,
                notificationId = action.notificationId,
                actedMessageIdHex = action.target.messageIdHex,
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                sinceMs = dismissBaselineMs,
            )
            Result.success().also { retryStore.clear(retryKey) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Log.w(TAG, "mark-read worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            markReadFailureResult(retryStore, retryKey)
        }
    }

    private fun markReadFailureResult(
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        val operationAttempt = retryStore.recordOperationFailureAttempt(retryKey)
        if (operationAttempt != null && shouldRetryAfterFailure(operationAttempt)) return Result.retry()
        retryStore.clear(retryKey)
        return Result.failure()
    }

    companion object {
        private const val TAG = "DMMarkReadWorker"
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_DELAY_SECONDS = 30L

        suspend fun enqueue(
            context: Context,
            action: NotificationAction,
        ): Boolean =
            try {
                WorkManager
                    .getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        notificationMarkReadWorkName(action),
                        ExistingWorkPolicy.KEEP,
                        notificationMarkReadRequest(action),
                    ).await()
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                Log.w(TAG, "failed to enqueue notification mark-read", failure)
                false
            }

        internal fun shouldRetryAfterFailure(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS - 1

        internal fun notificationMarkReadWorkName(action: NotificationAction): String {
            val canonical =
                listOf(
                    action.target.accountRef,
                    action.target.groupIdHex,
                    action.target.messageIdHex.orEmpty(),
                ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return "notification_mark_read_" +
                buildString(digest.size * 2) {
                    digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
                }
        }

        internal fun notificationMarkReadRequest(action: NotificationAction) =
            OneTimeWorkRequestBuilder<NotificationMarkReadWorker>()
                .setInputData(NotificationActionWorkData.encode(action))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                ).build()
    }
}
