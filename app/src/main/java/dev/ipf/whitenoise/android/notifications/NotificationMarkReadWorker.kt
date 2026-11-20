package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.markNotificationMessageReadIfAllowed
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
        val actionsAllowed =
            withContext(Dispatchers.Main.immediate) {
                application.appState.appLock.notificationActionsAllowed
            }
        if (!actionsAllowed) return lockedMarkReadResult(retryStore, retryKey)
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
                    if (!application.appState.appLock.notificationActionsAllowed) return@withContext null
                    application.appState.markNotificationMessageReadIfAllowed(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        messageIdHex = action.target.messageIdHex.orEmpty(),
                    )
                }
            if (markedRead == null) return lockedMarkReadResult(retryStore, retryKey)
            if (!markedRead) return markReadFailureResult(retryStore, retryKey)

            if (inputData.getBoolean(DISMISS_CARD_KEY, true)) {
                LocalNotificationPresenter(applicationContext).dismissActionNotificationAndOlderSiblings(
                    notificationTag = action.notificationTag,
                    notificationId = action.notificationId,
                    actedMessageIdHex = action.target.messageIdHex,
                    accountRef = action.target.accountRef,
                    groupIdHex = action.target.groupIdHex,
                    sinceMs = dismissBaselineMs,
                )
            }
            Result.success().also { retryStore.clear(retryKey) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            notificationWarning(TAG, "mark-read worker failed", throwable) {
                "group=${action.target.groupIdHex.take(8)}"
            }
            markReadFailureResult(retryStore, retryKey)
        }
    }

    /** Uses the independent lock-wait budget when the action cannot reach the runtime. */
    private fun lockedMarkReadResult(
        retryStore: NotificationActionRetryStore,
        retryKey: String,
    ): Result {
        if (retryStore.shouldDeferForLock(retryKey, NotificationActionRetryStore.MAXIMUM_LOCK_WAIT_MILLIS)) {
            return Result.retry()
        }
        return Result.failure().also { retryStore.clear(retryKey) }
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
        private const val DISMISS_CARD_KEY = "dismiss_card"
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_DELAY_SECONDS = 30L

        /** Keeps repeated actions durable, with a distinct key for a cursor-only update after sending. */
        suspend fun enqueue(
            context: Context,
            action: NotificationAction,
            dismissCard: Boolean = true,
        ): Boolean =
            try {
                WorkManager
                    .getInstance(context.applicationContext)
                    .enqueueUniqueWork(
                        notificationMarkReadWorkName(action, dismissCard),
                        ExistingWorkPolicy.KEEP,
                        notificationMarkReadRequest(action, dismissCard),
                    ).await()
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                Log.w(TAG, "notification_mark_read_enqueue_failed")
                false
            }

        internal fun shouldRetryAfterFailure(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS - 1

        /** Separates explicit card dismissal from cursor-only cleanup for the same message. */
        internal fun notificationMarkReadWorkName(
            action: NotificationAction,
            dismissCard: Boolean = true,
        ): String {
            val canonical =
                listOf(
                    action.target.accountRef,
                    action.target.groupIdHex,
                    action.target.messageIdHex.orEmpty(),
                ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            val suffix = if (dismissCard) "" else "_cursor"
            return buildString {
                append("notification_mark_read_")
                digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
                append(suffix)
            }
        }

        /** Records whether this action owns card dismissal along with its read cursor. */
        internal fun notificationMarkReadRequest(
            action: NotificationAction,
            dismissCard: Boolean = true,
        ): OneTimeWorkRequest {
            val input =
                Data
                    .Builder()
                    .putAll(NotificationActionWorkData.encode(action))
                    .putBoolean(DISMISS_CARD_KEY, dismissCard)
                    .build()
            return OneTimeWorkRequestBuilder<NotificationMarkReadWorker>()
                .setInputData(input)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_SECONDS,
                    TimeUnit.SECONDS,
                ).build()
        }
    }
}

/** Defers a denied read independently after a reply or reaction has already been accepted. */
internal suspend fun markReadAfterNotificationSend(
    context: Context,
    appState: WhiteNoiseAppState,
    action: NotificationAction,
): Boolean {
    val markedRead =
        withContext(Dispatchers.Main.immediate) {
            appState.markNotificationMessageReadIfAllowed(
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                messageIdHex = action.target.messageIdHex.orEmpty(),
            )
        }
    return markedRead ?: NotificationMarkReadWorker.enqueue(
        context,
        action.copy(kind = NotificationActionKind.MARK_READ),
        dismissCard = false,
    )
}
