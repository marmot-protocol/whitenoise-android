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
import kotlinx.coroutines.delay
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
        // Set when a direct reply was sent, so the cancel below can clear the
        // system's reply-lifetime-extension (a bare cancel can't dismiss it).
        var sentReplyText: String? = null
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
                            sentReplyText = reply
                            // mark-read is a best-effort UX nicety; a transient
                            // failure (or thrown FFI/network error) must never keep
                            // the notification alive, or its still-active inline
                            // RemoteInput field would let the user re-send the same
                            // reply and post a duplicate message to the group.
                            val markReadResult =
                                runCatching {
                                    appState.markNotificationMessageRead(
                                        accountRef = action.target.accountRef,
                                        groupIdHex = action.target.groupIdHex,
                                        messageIdHex = action.target.messageIdHex.orEmpty(),
                                    )
                                }
                            // Log a thrown error AND a plain false return; the
                            // latter (e.g. blank ids) would otherwise fail
                            // silently and hide best-effort mark-read trouble.
                            if (markReadResult.getOrNull() != true) {
                                val message =
                                    "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
                                        "message=${action.target.messageIdHex.orEmpty().take(8)}"
                                val throwable = markReadResult.exceptionOrNull()
                                if (throwable != null) {
                                    Log.w("DMNotifyAction", message, throwable)
                                } else {
                                    Log.w("DMNotifyAction", message)
                                }
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
            val presenter = LocalNotificationPresenter(appContext)
            val reply = sentReplyText
            if (reply != null) {
                // A sent direct reply leaves the notification lifetime-extended
                // by the system; a bare cancel() can't dismiss it. Signal
                // "reply handled" (setRemoteInputHistory) to clear the
                // extension, then cancel. The extension is applied a beat after
                // the broadcast fires, so retry the re-post until the live
                // notification appears, then let NMS settle before cancelling.
                var resolved = false
                repeat(REPLY_DISMISS_RETRIES) {
                    if (!resolved) {
                        resolved = presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reply)
                        if (!resolved) delay(REPLY_DISMISS_RETRY_DELAY_MS)
                    }
                }
                if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
            }
            presenter.cancel(action.notificationTag, action.notificationId)
        }
    }
}

internal fun notificationReplyActionHandled(sent: Boolean): Boolean = sent

// The system applies FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY a beat after the
// reply broadcast fires, so the live notification may not be in the active set
// on the first look; retry the "reply handled" re-post a few times, then give
// NMS a moment to clear the extension before cancelling.
private const val REPLY_DISMISS_RETRIES = 6
private const val REPLY_DISMISS_RETRY_DELAY_MS = 100L
private const val REPLY_DISMISS_SETTLE_MS = 350L
