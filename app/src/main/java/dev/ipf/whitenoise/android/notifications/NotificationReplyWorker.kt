package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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
import java.security.MessageDigest

class NotificationReplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? WhiteNoiseApplication ?: return Result.success()
        val action = notificationReplyActionFromInput(inputData) ?: return Result.success()
        val reply = inputData.getString(KEY_REPLY)?.trim().orEmpty()
        if (reply.isBlank()) return Result.success()
        if (!application.appState.notificationActionsAllowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply blocked by app lock group=${action.target.groupIdHex.take(8)}")
            return Result.success()
        }

        return try {
            val sent =
                withContext(Dispatchers.Main.immediate) {
                    application.appState.ensureNotificationRuntimeStarted()
                    application.appState.sendNotificationReply(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        text = reply,
                    )
                }
            if (sent) {
                markReadAfterReply(application, action)
                dismissSentReplyNotification(applicationContext, action, reply)
            }
            notificationReplyActionHandled(sent = sent)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "reply worker failed group=${action.target.groupIdHex.take(8)}", throwable)
            Result.retry()
        }
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
                presenter.cancel(action.notificationTag, action.notificationId)
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
        private const val UNIQUE_WORK_PREFIX = "notification_reply_"

        fun enqueue(
            context: Context,
            action: NotificationAction,
            reply: String,
        ) {
            runCatching {
                val request =
                    OneTimeWorkRequestBuilder<NotificationReplyWorker>()
                        .setInputData(notificationReplyInputData(action, reply))
                        .build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    notificationReplyWorkName(action, reply),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onFailure {
                if (BuildConfig.DEBUG) Log.w(TAG, "failed to enqueue reply worker", it)
            }
        }

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

        internal fun notificationReplyWorkName(
            action: NotificationAction,
            reply: String,
        ): String =
            UNIQUE_WORK_PREFIX +
                sha256Hex(
                    listOf(
                        action.target.accountRef,
                        action.target.groupIdHex,
                        action.notificationTag,
                        reply.trim(),
                    ).joinToString(separator = "\u0000"),
                ).take(32)

        private fun sha256Hex(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
