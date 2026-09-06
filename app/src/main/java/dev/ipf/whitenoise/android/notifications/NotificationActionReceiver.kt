package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.app.RemoteInput
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.CancellationException
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
        launchActionOrchestration(appContext, action, intent, finish = pending::finish)
    }

    /**
     * Runs one parsed action's enqueue orchestration off the main thread and
     * guarantees [finish] is invoked exactly once — on success, on failure,
     * and when the enqueue overruns [budgetMs] — so the broadcast's
     * [android.content.BroadcastReceiver.PendingResult] can never leak past
     * the receiver deadline. Exposed with an injectable [finish] and budget
     * because [goAsync]'s framework PendingResult offers no completion
     * observability under Robolectric.
     */
    @VisibleForTesting
    internal fun launchActionOrchestration(
        appContext: Context,
        action: NotificationAction,
        intent: Intent,
        finish: () -> Unit,
        budgetMs: Long = GO_ASYNC_BUDGET_MS,
        dispatchAction: suspend () -> Unit = {
            when (action.kind) {
                NotificationActionKind.REPLY -> enqueueReplyAction(appContext, action, intent)
                NotificationActionKind.REACT -> enqueueReactionAction(appContext, action, intent)
                NotificationActionKind.MARK_READ -> enqueueMarkReadAction(appContext, action)
            }
        },
    ) {
        // Keep receiver orchestration and WorkManager persistence off the main
        // thread; workers hop to main only for AppState mutations.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(budgetMs) {
                        dispatchAction()
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
                finish()
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
        val application = appContext as? WhiteNoiseApplication ?: return
        val actionStartedAtMs = System.currentTimeMillis()
        val reaction =
            notificationReactionChoice(
                intent = intent,
                allowedChoices = notificationQuickReactionChoices(appContext),
            ) ?: return
        val outcome =
            withContext(Dispatchers.IO) {
                submitNotificationReaction(
                    action = action,
                    reaction = reaction,
                    actionStartedAtMs = actionStartedAtMs,
                    notificationActionsAllowed = {
                        withContext(Dispatchers.Main.immediate) {
                            application.appState.notificationActionsAllowed
                        }
                    },
                    enqueueReaction = { queuedAction, queuedReaction, startedAtMs ->
                        NotificationReactionWorker.enqueue(
                            appContext,
                            queuedAction,
                            queuedReaction,
                            startedAtMs,
                        )
                    },
                    dismissNotification = { queuedAction, queuedReaction, startedAtMs ->
                        dismissReactedNotification(
                            presenter = LocalNotificationPresenter(appContext),
                            action = queuedAction,
                            reaction = queuedReaction,
                            dismissalBaselineMs = startedAtMs,
                        )
                    },
                )
            }
        when (outcome) {
            NotificationReactionSubmissionOutcome.PersistenceFailed ->
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(appContext, R.string.toast_reaction_failed, Toast.LENGTH_LONG).show()
                }
            NotificationReactionSubmissionOutcome.BlockedByAppLock ->
                notificationWarning("DMNotifyAction", "notification reaction blocked by app lock") {
                    "group=${action.target.groupIdHex.take(LOGGED_GROUP_ID_PREFIX)}"
                }
            NotificationReactionSubmissionOutcome.Rejected,
            NotificationReactionSubmissionOutcome.Submitted,
            -> Unit
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

// The reaction PendingIntent is mutable so SystemUI can attach RemoteInput
// results. Accept only a normalized value from the current configured choices.
internal fun notificationReactionChoice(
    intent: Intent,
    allowedChoices: List<String>,
): String? {
    val results = RemoteInput.getResultsFromIntent(intent)
    val reaction =
        normalizeNotificationReaction(
            (
                results?.getCharSequence(NotificationActions.KEY_REACTION_CHOICE)
                    ?: results
                        ?.getCharSequence(NotificationActions.KEY_TEXT_REPLY)
                        ?.takeIf { NotificationActions.isInlineReactionChoice(intent) }
            )?.toString(),
        ) ?: NotificationActions.legacyReaction(intent)
    return reaction?.takeIf { it in allowedChoices }
}

internal enum class NotificationReactionSubmissionOutcome {
    Submitted,
    BlockedByAppLock,
    Rejected,
    PersistenceFailed,
}

/** Persist the encrypted mutation before changing visible notification state. */
@Suppress("ReturnCount") // Guard outcomes must not mutate or dismiss the card.
internal suspend fun submitNotificationReaction(
    action: NotificationAction,
    reaction: String,
    actionStartedAtMs: Long = System.currentTimeMillis(),
    notificationActionsAllowed: suspend () -> Boolean,
    enqueueReaction: suspend (NotificationAction, String, Long) -> Boolean,
    dismissNotification: suspend (NotificationAction, String, Long) -> Unit,
): NotificationReactionSubmissionOutcome {
    if (action.kind != NotificationActionKind.REACT || actionStartedAtMs <= 0L) {
        return NotificationReactionSubmissionOutcome.Rejected
    }
    val normalizedReaction =
        normalizeNotificationReaction(reaction)
            ?: return NotificationReactionSubmissionOutcome.Rejected
    if (!notificationActionsAllowed()) return NotificationReactionSubmissionOutcome.BlockedByAppLock
    if (!enqueueReaction(action, normalizedReaction, actionStartedAtMs)) {
        return NotificationReactionSubmissionOutcome.PersistenceFailed
    }

    dismissNotification(action, normalizedReaction, actionStartedAtMs)
    return NotificationReactionSubmissionOutcome.Submitted
}

/**
 * Clear the RemoteInput lifetime extension and then cancel only the card
 * generation that offered the selected reaction. Sibling cleanup uses the tap
 * timestamp so work delayed by connectivity can never consume newer events.
 */
internal suspend fun dismissReactedNotification(
    presenter: LocalNotificationPresenter,
    action: NotificationAction,
    reaction: String,
    dismissalBaselineMs: Long,
) {
    val handled =
        runCatching {
            retryReplyCardRestore {
                presenter.markReactionHandledIfSameGeneration(
                    notificationTag = action.notificationTag,
                    notificationId = action.notificationId,
                    reactedMessageIdHex = action.target.messageIdHex,
                    reaction = reaction,
                )
            }
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }
    if (handled) delay(REPLY_DISMISS_SETTLE_MS)
    runCatching {
        presenter.dismissActionNotificationAndOlderSiblings(
            notificationTag = action.notificationTag,
            notificationId = action.notificationId,
            actedMessageIdHex = action.target.messageIdHex,
            accountRef = action.target.accountRef,
            groupIdHex = action.target.groupIdHex,
            sinceMs = dismissalBaselineMs,
        )
    }.onFailure { failure ->
        if (failure is CancellationException) throw failure
        notificationWarning("DMNotifyAction", "notification reaction cleanup failed", failure) {
            "group=${action.target.groupIdHex.take(LOGGED_GROUP_ID_PREFIX)}"
        }
    }
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

// The system applies FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY a beat after the
// reply broadcast fires, so the live notification may not be in the active set
// on the first look; retry the "reply handled" re-post a few times, then give
// NMS a moment to clear the extension before cancelling.
// Keep under the manifest receiver's ~10s goAsync() deadline, leaving margin for
// pending.finish() so a slow/cold send can't get the process killed mid-reply.
private const val GO_ASYNC_BUDGET_MS = 8_000L
private const val GO_ASYNC_FINISH_MARGIN_MS = 300L
private const val LOGGED_GROUP_ID_PREFIX = 8
internal const val REPLY_DISMISS_RETRIES = 6
internal const val REPLY_DISMISS_RETRY_DELAY_MS = 100L
internal const val REPLY_DISMISS_SETTLE_MS = 350L
