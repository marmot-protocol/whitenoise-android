package dev.ipf.darkmatter.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.ipf.darkmatter.DarkMatterApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = NotificationActions.parse(intent) ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                handleAction(context.applicationContext, action, intent)
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

    private suspend fun handleAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val application = appContext as? DarkMatterApplication ?: return
        val appState = application.appState
        appState.ensureNotificationRuntimeStarted()
        val handled =
            when (action.kind) {
                NotificationActionKind.REPLY -> {
                    val reply =
                        RemoteInput
                            .getResultsFromIntent(intent)
                            ?.getCharSequence(NotificationActions.KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                    if (reply.isBlank()) {
                        false
                    } else {
                        val sent =
                            appState.sendNotificationReply(
                                accountRef = action.target.accountRef,
                                groupIdHex = action.target.groupIdHex,
                                text = reply,
                            )
                        if (sent) {
                            // mark-read is a best-effort UX nicety; a transient
                            // failure (or thrown FFI/network error) must never keep
                            // the notification alive, or its still-active inline
                            // RemoteInput field would let the user re-send the same
                            // reply and post a duplicate message to the group.
                            runCatching {
                                appState.markNotificationMessageRead(
                                    accountRef = action.target.accountRef,
                                    groupIdHex = action.target.groupIdHex,
                                    messageIdHex = action.target.messageIdHex.orEmpty(),
                                )
                            }.onFailure { throwable ->
                                Log.w(
                                    "DMNotifyAction",
                                    "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
                                        "message=${action.target.messageIdHex.orEmpty().take(8)}",
                                    throwable,
                                )
                            }
                        }
                        notificationReplyActionHandled(sent = sent)
                    }
                }

                NotificationActionKind.MARK_READ ->
                    appState.markNotificationMessageRead(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        messageIdHex = action.target.messageIdHex.orEmpty(),
                    )
            }

        if (handled) {
            LocalNotificationPresenter(appContext).cancel(action.notificationTag, action.notificationId)
        }
    }
}

internal fun notificationReplyActionHandled(sent: Boolean): Boolean = sent
