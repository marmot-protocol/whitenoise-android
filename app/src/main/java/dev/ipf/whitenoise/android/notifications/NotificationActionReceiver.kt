package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = NotificationActions.parse(intent) ?: return
        when (action.kind) {
            NotificationActionKind.REPLY -> enqueueReplyAction(context.applicationContext, action, intent)
            NotificationActionKind.MARK_READ -> handleMarkReadAsync(context.applicationContext, action)
        }
    }

    private fun enqueueReplyAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val reply = notificationReplyTextFrom(intent)
        if (reply.isBlank()) return
        if (!tryBeginNotificationReplyDispatch(action, reply)) {
            Log.w(
                "DMNotifyAction",
                "duplicate notification reply ignored group=${action.target.groupIdHex.take(8)}",
            )
            return
        }
        if (!NotificationReplyWorker.enqueue(appContext, action, reply)) {
            finishNotificationReplyDispatch(action, reply, keepRecent = false)
        }
    }

    private fun handleMarkReadAsync(
        appContext: Context,
        action: NotificationAction,
    ) {
        val pending = goAsync()
        // Mark-read still does FFI/relay/Binder work, so keep it off the main
        // thread and bounded below the BroadcastReceiver deadline. Direct replies
        // are different: their potentially blocking send runs in WorkManager.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                        handleMarkReadAction(appContext, action)
                        true
                    }
                if (completed == null) {
                    Log.w(
                        "DMNotifyAction",
                        "notification action timed out kind=${action.kind} group=${action.target.groupIdHex.take(8)}",
                    )
                }
            } catch (throwable: Throwable) {
                Log.w(
                    "DMNotifyAction",
                    "notification action failed kind=${action.kind} group=${action.target.groupIdHex.take(8)} " +
                        "message=${action.target.messageIdHex.orEmpty().take(8)}",
                    throwable,
                )
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun handleMarkReadAction(
        appContext: Context,
        action: NotificationAction,
    ) {
        val application = appContext as? WhiteNoiseApplication ?: return
        val appState = application.appState
        if (!appState.notificationActionsAllowed) {
            Log.w(
                "DMNotifyAction",
                "notification action blocked by app lock kind=${action.kind} group=${action.target.groupIdHex.take(8)}",
            )
            return
        }
        appState.ensureNotificationRuntimeStarted()
        if (
            appState.markNotificationMessageRead(
                accountRef = action.target.accountRef,
                groupIdHex = action.target.groupIdHex,
                messageIdHex = action.target.messageIdHex.orEmpty(),
            )
        ) {
            LocalNotificationPresenter(appContext).cancel(action.notificationTag, action.notificationId)
        }
    }
}

// Keep the broadcast work below the platform's ~10s goAsync() deadline. Reply
// sends no longer consume this budget; they are enqueued to WorkManager and sent
// after the receiver returns.
private const val GO_ASYNC_BUDGET_MS = 8_000L
