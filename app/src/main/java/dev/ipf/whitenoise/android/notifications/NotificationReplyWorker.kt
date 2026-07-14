package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class NotificationReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val action = notificationReplyActionFromInput(inputData) ?: return Result.success()
        val reply = inputData.getString(KEY_REPLY)?.trim().orEmpty()
        if (reply.isBlank()) return Result.success()
        val completionStore = NotificationReplyCompletionStore.create(applicationContext)
        // WorkManager keeps this id stable across retries and assigns a new one
        // to every separately enqueued reply, even when the text is identical.
        val completionKey = notificationReplyCompletionKey(id)
        if (!application.appState.notificationActionsAllowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply blocked by app lock group=${action.target.groupIdHex.take(8)}")
            return Result.success()
        }

        return try {
            withContext(Dispatchers.Main.immediate) {
                application.appState.ensureNotificationRuntimeStarted()
            }
            if (
                completionStore.isCompleted(completionKey) ||
                (completionStore.hasStarted(completionKey) && alreadyCommitted(application, action, reply))
            ) {
                completionStore.markCompleted(completionKey)
                markReadAfterReply(application, action)
                dismissSentReplyNotification(applicationContext, action, reply)
                notificationReplyActionHandled(sent = true)
                return Result.success()
            }
            completionStore.markStarted(completionKey)
            val sent =
                withContext(Dispatchers.Main.immediate) {
                    application.appState.sendNotificationReply(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        text = reply,
                    )
                }
            if (sent) {
                completionStore.markCompleted(completionKey)
                markReadAfterReply(application, action)
                dismissSentReplyNotification(applicationContext, action, reply)
                notificationReplyActionHandled(sent = true)
                Result.success()
            } else {
                notificationReplyActionHandled(sent = false)
                if (BuildConfig.DEBUG) Log.w(TAG, "reply send returned false group=${action.target.groupIdHex.take(8)}")
                replyFailureResult(action)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            replyFailureResult(action)
        }
    }

    private suspend fun alreadyCommitted(
        application: WhiteNoiseApplication,
        action: NotificationAction,
        reply: String,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            application.appState.notificationReplyAlreadyCommitted(
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                afterMessageIdHex = action.target.messageIdHex.orEmpty(),
                text = reply,
            )
        }

    private fun replyFailureResult(action: NotificationAction): Result {
        if (shouldRetryAfterFailure(runAttemptCount)) return Result.retry()
        Log.w(TAG, "reply retry limit reached group=${action.target.groupIdHex.take(8)} attempts=${runAttemptCount + 1}")
        return Result.failure()
    }

    private suspend fun markReadAfterReply(
        application: WhiteNoiseApplication,
        action: NotificationAction,
    ) {
        val markReadFailureMessage =
            "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
                "message=${action.target.messageIdHex.orEmpty().take(8)}"
        val result =
            try {
                withContext(Dispatchers.Main.immediate) {
                    application.appState.markNotificationMessageRead(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        messageIdHex = action.target.messageIdHex.orEmpty(),
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Log.w(TAG, markReadFailureMessage, throwable)
                null
            }
        if (result == false) Log.w(TAG, markReadFailureMessage)
    }

    private suspend fun dismissSentReplyNotification(
        appContext: Context,
        action: NotificationAction,
        reply: String,
    ) {
        val presenter = LocalNotificationPresenter(appContext)
        // Baseline for sparing sibling cards that arrive during the retry+settle
        // window below: only cards already present when we start are cleared.
        val dismissBaselineMs = System.currentTimeMillis()
        withContext(NonCancellable) {
            try {
                var resolved = false
                repeat(REPLY_DISMISS_RETRIES) {
                    if (!resolved) {
                        resolved = presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reply)
                        if (!resolved) delay(REPLY_DISMISS_RETRY_DELAY_MS)
                    }
                }
                if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
            } finally {
                // The replied card was deliberately re-posted above to clear the
                // direct-reply lifetime extension. Cancel it only when its stamped
                // latest-message id still matches the replied action target.
                presenter.cancelRepliedConversationCardIfSameGeneration(
                    notificationTag = action.notificationTag,
                    notificationId = action.notificationId,
                    repliedMessageIdHex = action.target.messageIdHex,
                )
                presenter.dismissConversationSiblingCardsNotNewerThan(
                    accountRef = action.target.accountRef,
                    groupIdHex = action.target.groupIdHex,
                    sinceMs = dismissBaselineMs,
                )
            }
        }
    }

    companion object {
        private const val TAG = "DMReplyWorker"
        private const val KEY_ACTION = "action"
        private const val KEY_ACCOUNT_REF = "account_ref"
        private const val KEY_GROUP_ID_HEX = "group_id_hex"
        private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
        private const val KEY_TARGET_KIND = "target_kind"
        private const val KEY_NOTIFICATION_TAG = "notification_tag"
        private const val KEY_NOTIFICATION_ID = "notification_id"
        private const val KEY_REPLY = "reply"
        private const val COMPLETION_KEY_PREFIX = "notification_reply_"
        private const val MAX_SEND_ATTEMPTS = 3
        private const val REPLY_BACKOFF_DELAY_SECONDS = 30L

        fun enqueue(
            context: Context,
            action: NotificationAction,
            reply: String,
        ) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueue(notificationReplyRequest(action, reply))
            }.onFailure {
                if (BuildConfig.DEBUG) Log.w(TAG, "failed to enqueue reply worker", it)
            }
        }

        internal fun shouldRetryAfterFailure(runAttemptCount: Int): Boolean = runAttemptCount < MAX_SEND_ATTEMPTS - 1

        internal fun notificationReplyRequest(
            action: NotificationAction,
            reply: String,
        ) = OneTimeWorkRequestBuilder<NotificationReplyWorker>()
            .setInputData(notificationReplyInputData(action, reply))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                REPLY_BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            ).build()

        internal fun notificationReplyInputData(
            action: NotificationAction,
            reply: String,
        ): Data =
            workDataOf(
                KEY_ACTION to NotificationActions.ACTION_REPLY,
                KEY_ACCOUNT_REF to action.target.accountRef,
                KEY_GROUP_ID_HEX to action.target.groupIdHex,
                KEY_MESSAGE_ID_HEX to action.target.messageIdHex.orEmpty(),
                KEY_TARGET_KIND to action.target.kind.name,
                KEY_NOTIFICATION_TAG to action.notificationTag,
                KEY_NOTIFICATION_ID to action.notificationId,
                KEY_REPLY to reply,
            )

        internal fun notificationReplyActionFromInput(data: Data): NotificationAction? =
            NotificationActions.parseRawFields(
                action = data.getString(KEY_ACTION),
                accountRef = data.getString(KEY_ACCOUNT_REF),
                groupIdHex = data.getString(KEY_GROUP_ID_HEX),
                messageIdHex = data.getString(KEY_MESSAGE_ID_HEX),
                targetKindName = data.getString(KEY_TARGET_KIND),
                notificationTag = data.getString(KEY_NOTIFICATION_TAG),
                notificationId = data.getInt(KEY_NOTIFICATION_ID, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
            )

        internal fun notificationReplyCompletionKey(workRequestId: UUID): String = COMPLETION_KEY_PREFIX + workRequestId
    }
}
