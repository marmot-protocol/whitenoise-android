package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class NotificationMarkReadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val action =
            NotificationActionWorkData
                .decode(inputData)
                ?.takeIf { it.kind == NotificationActionKind.MARK_READ }
                ?: return Result.failure()
        if (!application.appState.notificationActionsAllowed) {
            Log.w(TAG, "mark-read deferred by app lock group=${action.target.groupIdHex.take(8)}")
            return Result.retry()
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
            if (!markedRead) return markReadFailureResult()

            LocalNotificationPresenter(applicationContext).dismissActionNotificationAndOlderSiblings(
                notificationTag = action.notificationTag,
                notificationId = action.notificationId,
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                sinceMs = dismissBaselineMs,
            )
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Log.w(TAG, "mark-read worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            markReadFailureResult()
        }
    }

    private fun markReadFailureResult(): Result = if (shouldRetryAfterFailure(runAttemptCount)) Result.retry() else Result.failure()

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
                    .enqueue(notificationMarkReadRequest(action))
                    .await()
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                Log.w(TAG, "failed to enqueue notification mark-read", failure)
                false
            }

        internal fun shouldRetryAfterFailure(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS - 1

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
