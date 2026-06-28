package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = NotificationActions.parse(intent) ?: return
        val pending = goAsync()
        // Off the main thread: the handler does FFI/relay/Binder work. Bounded by
        // a budget below the ~10s goAsync deadline so pending.finish() always runs
        // and the broadcast is never killed mid-flight with the reply dropped.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                        handleAction(context.applicationContext, action, intent)
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

    private suspend fun handleAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val application = appContext as? WhiteNoiseApplication ?: return
        val appState = application.appState
        when (action.kind) {
            NotificationActionKind.REPLY -> handleReplyAction(appContext, appState, action, intent)
            NotificationActionKind.MARK_READ -> {
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
    }

    private suspend fun handleReplyAction(
        appContext: Context,
        appState: WhiteNoiseAppState,
        action: NotificationAction,
        intent: Intent,
    ) {
        val reply =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(NotificationActions.KEY_TEXT_REPLY)
                ?.toString()
                ?.trim()
                .orEmpty()
        if (reply.isBlank()) return

        // Set as soon as the FFI send reports success. The finally block below
        // then owns RemoteInput cleanup even if best-effort mark-read stalls or
        // the reserved send/bootstrap phase times out after the send completed.
        var sentReplyText: String? = null
        try {
            val completed =
                // This timeout is cooperative: slow JNI/Binder work can run past
                // it until the next suspension point. The split still reserves a
                // best-effort dismiss window after a successful send, trading a
                // shorter cold-start send phase for lower duplicate-send risk.
                withTimeoutOrNull(REPLY_SEND_PHASE_BUDGET_MS) {
                    appState.ensureNotificationRuntimeStarted()
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
                        val markReadFailureMessage =
                            "reply sent but mark-read failed group=${action.target.groupIdHex.take(8)} " +
                                "message=${action.target.messageIdHex.orEmpty().take(8)}"
                        val markReadResult =
                            try {
                                appState.markNotificationMessageRead(
                                    accountRef = action.target.accountRef,
                                    groupIdHex = action.target.groupIdHex,
                                    messageIdHex = action.target.messageIdHex.orEmpty(),
                                )
                            } catch (throwable: Throwable) {
                                if (throwable is CancellationException) throw throwable
                                Log.w("DMNotifyAction", markReadFailureMessage, throwable)
                                null
                            }
                        // Log a thrown error AND a plain false return; the
                        // latter (e.g. blank ids) would otherwise fail
                        // silently and hide best-effort mark-read trouble.
                        if (markReadResult == false) {
                            Log.w("DMNotifyAction", markReadFailureMessage)
                        }
                    }
                    notificationReplyActionHandled(sent = sent)
                }
            if (completed == null) {
                Log.w(
                    "DMNotifyAction",
                    "notification reply send phase timed out group=${action.target.groupIdHex.take(8)}",
                )
            }
        } finally {
            sentReplyText?.let {
                dismissSentReplyNotification(appContext, action, it)
            }
        }
    }

    private suspend fun dismissSentReplyNotification(
        appContext: Context,
        action: NotificationAction,
        reply: String,
    ) {
        val presenter = LocalNotificationPresenter(appContext)
        withContext(NonCancellable) {
            try {
                val completed =
                    // Also cooperative: re-post/cancel are Binder-facing and may
                    // only observe timeout at suspension points between retries.
                    withTimeoutOrNull(REPLY_DISMISS_BUDGET_MS) {
                        // A sent direct reply leaves the notification
                        // lifetime-extended by the system; a bare cancel() can't
                        // dismiss it. Signal "reply handled"
                        // (setRemoteInputHistory) to clear the extension, then
                        // cancel. The extension is applied a beat after the
                        // broadcast fires, so retry the re-post until the live
                        // notification appears, then let NMS settle before
                        // cancelling.
                        var resolved = false
                        repeat(REPLY_DISMISS_RETRIES) {
                            if (!resolved) {
                                resolved = presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reply)
                                if (!resolved) delay(REPLY_DISMISS_RETRY_DELAY_MS)
                            }
                        }
                        if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
                        true
                    }
                if (completed == null) {
                    Log.w(
                        "DMNotifyAction",
                        "notification reply dismiss timed out group=${action.target.groupIdHex.take(8)}",
                    )
                }
            } finally {
                presenter.cancel(action.notificationTag, action.notificationId)
            }
        }
    }
}

internal fun notificationReplyActionHandled(sent: Boolean): Boolean = sent

internal fun notificationReplyDismissBudgetMs(
    retries: Int = REPLY_DISMISS_RETRIES,
    retryDelayMs: Long = REPLY_DISMISS_RETRY_DELAY_MS,
    settleMs: Long = REPLY_DISMISS_SETTLE_MS,
): Long = retries * retryDelayMs + settleMs

internal fun notificationReplySendPhaseBudgetMs(
    goAsyncBudgetMs: Long = GO_ASYNC_BUDGET_MS,
    dismissBudgetMs: Long = notificationReplyDismissBudgetMs(),
    finishMarginMs: Long = GO_ASYNC_FINISH_MARGIN_MS,
): Long = (goAsyncBudgetMs - dismissBudgetMs - finishMarginMs).coerceAtLeast(1L)

// The system applies FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY a beat after the
// reply broadcast fires, so the live notification may not be in the active set
// on the first look; retry the "reply handled" re-post a few times, then give
// NMS a moment to clear the extension before cancelling.
// Keep under the manifest receiver's ~10s goAsync() deadline, leaving margin for
// pending.finish() so a slow/cold send can't get the process killed mid-reply.
private const val GO_ASYNC_BUDGET_MS = 8_000L
private const val GO_ASYNC_FINISH_MARGIN_MS = 300L
private val REPLY_DISMISS_BUDGET_MS = notificationReplyDismissBudgetMs()
private val REPLY_SEND_PHASE_BUDGET_MS = notificationReplySendPhaseBudgetMs()
private const val REPLY_DISMISS_RETRIES = 6
private const val REPLY_DISMISS_RETRY_DELAY_MS = 100L
private const val REPLY_DISMISS_SETTLE_MS = 350L
