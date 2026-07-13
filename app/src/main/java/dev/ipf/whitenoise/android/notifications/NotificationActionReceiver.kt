package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = NotificationActions.parse(intent) ?: return
        if (action.kind == NotificationActionKind.REPLY) {
            enqueueReplyAction(context.applicationContext, action, intent)
            return
        }
        val pending = goAsync()
        // Keep receiver orchestration and presenter Binder work off the main
        // thread; handleAction hops to main only for AppState mutations.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                        handleAction(context.applicationContext, action)
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

    private fun enqueueReplyAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val application = appContext as? WhiteNoiseApplication ?: return
        if (!application.appState.notificationActionsAllowed) {
            Log.w(
                "DMNotifyAction",
                "notification reply blocked by app lock group=${action.target.groupIdHex.take(8)}",
            )
            return
        }
        val reply =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(NotificationActions.KEY_TEXT_REPLY)
                ?.toString()
                ?.trim()
                .orEmpty()
        if (reply.isBlank()) return
        NotificationReplyWorker.enqueue(appContext, action, reply)
    }

    private suspend fun handleAction(
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
        when (action.kind) {
            NotificationActionKind.REPLY -> return
            NotificationActionKind.MARK_READ -> {
                // Baseline before the mark-read round-trip so a message, reaction,
                // or mention arriving while it runs keeps its card.
                val dismissBaselineMs = System.currentTimeMillis()
                val markedRead =
                    withContext(Dispatchers.Main.immediate) {
                        appState.ensureNotificationRuntimeStarted()
                        appState.markNotificationMessageRead(
                            accountRef = action.target.accountRef,
                            groupIdHex = action.target.groupIdHex,
                            messageIdHex = action.target.messageIdHex.orEmpty(),
                        )
                    }
                if (markedRead) {
                    val presenter = LocalNotificationPresenter(appContext)
                    presenter.cancel(action.notificationTag, action.notificationId)
                    presenter.dismissConversationSiblingCardsNotNewerThan(
                        accountRef = action.target.accountRef,
                        groupIdHex = action.target.groupIdHex,
                        sinceMs = dismissBaselineMs,
                    )
                }
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
internal const val REPLY_DISMISS_RETRIES = 6
internal const val REPLY_DISMISS_RETRY_DELAY_MS = 100L
internal const val REPLY_DISMISS_SETTLE_MS = 350L
