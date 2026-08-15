package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val appContext = context.applicationContext
        val pending = goAsync()
        // Keep receiver orchestration and WorkManager persistence off the main
        // thread; workers hop to main only for AppState mutations.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(GO_ASYNC_BUDGET_MS) {
                        when (action.kind) {
                            NotificationActionKind.REPLY -> enqueueReplyAction(appContext, action, intent)
                            NotificationActionKind.REACT -> enqueueReactionAction(appContext, action, intent)
                            NotificationActionKind.MARK_READ -> enqueueMarkReadAction(appContext, action)
                        }
                        true
                    }
                if (completed == null) {
                    notificationWarning("DMNotifyAction", "notification action timed out kind=${action.kind}") {
                        "group=${action.target.groupIdHex.take(8)}"
                    }
                }
            } catch (throwable: Throwable) {
                notificationWarning("DMNotifyAction", "notification action failed kind=${action.kind}", throwable) {
                    "group=${action.target.groupIdHex.take(8)} message=${action.target.messageIdHex.orEmpty().take(8)}"
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun enqueueReplyAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val application = appContext as? WhiteNoiseApplication ?: return
        val notificationActionsAllowed =
            withContext(Dispatchers.Main.immediate) {
                application.appState.notificationActionsAllowed
            }
        if (!notificationActionsAllowed) {
            notificationWarning("DMNotifyAction", "notification reply blocked by app lock") {
                "group=${action.target.groupIdHex.take(8)}"
            }
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
        val enqueued =
            withContext(Dispatchers.IO) {
                NotificationReplyWorker.enqueue(appContext, action, reply)
            }
        if (!enqueued) {
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(appContext, R.string.toast_send_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun enqueueReactionAction(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
    ) {
        val reaction =
            notificationReactionChoice(
                intent = intent,
                allowedChoices = notificationQuickReactionChoices(appContext),
            ) ?: return
        val dismissalBaselineMs = System.currentTimeMillis()
        val queued =
            withContext(Dispatchers.IO) {
                submitNotificationReaction(
                    action = action,
                    reaction = reaction,
                    enqueueReactionAndMarkRead = { queuedAction, queuedReaction ->
                        NotificationReactionWorker.enqueueActionBatch(appContext, queuedAction, queuedReaction)
                    },
                    dismissNotification = { queuedAction, queuedReaction ->
                        dismissReactedNotification(
                            presenter = LocalNotificationPresenter(appContext),
                            action = queuedAction,
                            reaction = queuedReaction,
                            dismissalBaselineMs = dismissalBaselineMs,
                        )
                    },
                )
            }
        if (!queued) {
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(appContext, R.string.toast_reaction_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun enqueueMarkReadAction(
        appContext: Context,
        action: NotificationAction,
    ) {
        val enqueued =
            withContext(Dispatchers.IO) {
                NotificationMarkReadWorker.enqueue(appContext, action)
            }
        if (!enqueued) {
            notificationWarning("DMNotifyAction", "failed to persist notification mark-read") {
                "group=${action.target.groupIdHex.take(8)}"
            }
        }
    }
}

internal fun notificationReplyActionHandled(sent: Boolean): Boolean = sent

// The action PendingIntent must be mutable so SystemUI can attach RemoteInput
// results. Treat that result as untrusted and accept only a currently configured
// quick-reaction choice.
internal fun notificationReactionChoice(
    intent: Intent,
    allowedChoices: List<String>,
): String? {
    val reaction =
        normalizeNotificationReaction(
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(NotificationActions.KEY_REACTION_CHOICE)
                ?.toString(),
        )
    return reaction?.takeIf { it in allowedChoices }
}

/**
 * Clears the reacted card. A chip tap is a RemoteInput send, so the system
 * lifetime-extends the notification and swallows a bare cancel — the extension
 * has to be cleared with the same "handled" re-post the reply path uses (and
 * given a beat to settle) before the cancel will take.
 */
internal suspend fun dismissReactedNotification(
    presenter: LocalNotificationPresenter,
    action: NotificationAction,
    reaction: String,
    dismissalBaselineMs: Long,
) {
    val resolved =
        runCatching {
            retryReplyCardRestore {
                presenter.markDirectReplyHandled(action.notificationTag, action.notificationId, reaction)
            }
        }.getOrDefault(false)
    if (resolved) delay(REPLY_DISMISS_SETTLE_MS)
    runCatching {
        presenter.dismissActionNotificationAndOlderSiblings(
            notificationTag = action.notificationTag,
            notificationId = action.notificationId,
            actedMessageIdHex = action.target.messageIdHex,
            accountRef = action.target.accountRef,
            groupIdHex = action.target.groupIdHex,
            sinceMs = dismissalBaselineMs,
        )
    }.onFailure {
        notificationWarning("DMNotifyAction", "notification reaction cleanup failed", it) {
            "group=${action.target.groupIdHex.take(LOGGED_GROUP_ID_PREFIX)}"
        }
    }
}

/**
 * Persists the reaction and mark-read jobs atomically before clearing any
 * visible state. The card stays available if the WorkManager transaction fails
 * or is cancelled, so a retry cannot observe only one queued side effect.
 */
@Suppress("ReturnCount") // Each guard aborts before the notification is dismissed.
internal suspend fun submitNotificationReaction(
    action: NotificationAction,
    reaction: String,
    enqueueReactionAndMarkRead: suspend (NotificationAction, String) -> Boolean,
    dismissNotification: suspend (NotificationAction, String) -> Unit,
): Boolean {
    if (action.kind != NotificationActionKind.REACT) return false
    val normalizedReaction = normalizeNotificationReaction(reaction) ?: return false
    if (!enqueueReactionAndMarkRead(action, normalizedReaction)) return false

    dismissNotification(action, normalizedReaction)
    return true
}

/**
 * Bounded retry for restoring a replied conversation card, mirroring the
 * success path's re-post loop: the system applies the direct-reply lifetime
 * extension a beat after the reply broadcast, so the first look can miss the
 * card. Returns true as soon as [restore] succeeds, false once the attempts
 * are exhausted (card gone or replaced — the caller's toast is then the only
 * signal).
 */
internal suspend fun retryReplyCardRestore(
    attempts: Int = REPLY_DISMISS_RETRIES,
    retryDelayMs: Long = REPLY_DISMISS_RETRY_DELAY_MS,
    restore: () -> Boolean,
): Boolean {
    repeat(attempts) { attempt ->
        if (restore()) return true
        if (attempt < attempts - 1) delay(retryDelayMs)
    }
    return false
}

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

// Group ids are PII-adjacent, so logs carry only a short correlating prefix.
private const val LOGGED_GROUP_ID_PREFIX = 8

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
