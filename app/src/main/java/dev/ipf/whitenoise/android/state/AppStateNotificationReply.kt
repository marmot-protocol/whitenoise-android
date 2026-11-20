package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.exceedsOutgoingMessageLimit
import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyCommitProbe
import dev.ipf.whitenoise.android.notifications.NotificationReplyCompletionStore
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryBoundary
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryLookup
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryState
import dev.ipf.whitenoise.android.notifications.NotificationReplySendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyTimelinePage
import dev.ipf.whitenoise.android.notifications.NotificationReplyTimelineRecord
import dev.ipf.whitenoise.android.notifications.normalizeNotificationReaction
import dev.ipf.whitenoise.android.notifications.notificationReactionOutcome
import dev.ipf.whitenoise.android.notifications.notificationReplyRecoveryBoundary
import dev.ipf.whitenoise.android.notifications.notificationReplySendWindowReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import dev.ipf.whitenoise.android.notifications.notificationReplyCommitProbe as probeNotificationReplyCommit

private const val NOTIFICATION_REPLY_SEND_WINDOW_POLL_MILLIS = 25L
private const val REPLY_LOG_GROUP_LENGTH = 8

/**
 * Sends one notification reply exactly once, reporting an earlier attempt that already committed instead of
 * resending it, and refusing a reply over the outgoing limit before anything is sent.
 */
internal suspend fun WhiteNoiseAppState.sendNotificationReply(
    accountRef: String,
    groupIdHex: String,
    afterMessageIdHex: String,
    text: String,
    completionStore: NotificationReplyCompletionStore,
    completionKey: String,
    recoveryScope: String,
): NotificationReplySendOutcome {
    val account = accountRef.takeIf { it.isNotBlank() } ?: return NotificationReplySendOutcome.NonRetryableFailure
    val group = groupIdHex.takeIf { it.isNotBlank() } ?: return NotificationReplySendOutcome.NonRetryableFailure
    if (!ConversationController.HEX_MESSAGE_ID.matches(afterMessageIdHex)) {
        return NotificationReplySendOutcome.NonRetryableFailure
    }
    val body = text.trim().takeIf { it.isNotEmpty() } ?: return NotificationReplySendOutcome.NonRetryableFailure
    return runCatchingCancellable {
        withGroupCommitLock(account, group) {
            val recoveryLookup =
                withContext(Dispatchers.IO) {
                    completionStore.recoveryLookup(completionKey)
                }
            val recoverySnapshot =
                when (recoveryLookup) {
                    NotificationReplyRecoveryLookup.NotStarted -> null
                    NotificationReplyRecoveryLookup.Indeterminate ->
                        return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                    is NotificationReplyRecoveryLookup.Ready -> recoveryLookup.snapshot
                }
            if (recoverySnapshot != null) {
                when (
                    notificationReplyCommitState(
                        account = account,
                        group = group,
                        recoveryState = recoverySnapshot.recoveryState,
                        nextAttemptBoundary = recoverySnapshot.nextAttemptBoundary,
                        text = body,
                    )
                ) {
                    NotificationReplyCommitProbe.Committed ->
                        return@withGroupCommitLock NotificationReplySendOutcome.AlreadyCommitted
                    NotificationReplyCommitProbe.Indeterminate ->
                        return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                    NotificationReplyCommitProbe.NotCommitted -> Unit
                }
            }
            // A reply queued before the limit existed may be over it. Refuse it only now, once the
            // probe above has ruled out an earlier attempt that already committed it.
            if (exceedsOutgoingMessageLimit(body)) return@withGroupCommitLock NotificationReplySendOutcome.TooLong

            // A synthetic max-id cursor excludes every row in this wall-clock
            // second. Wait for the next second before asking MDK to build the
            // event so its random event id cannot sort below the lower bound.
            val recoveryBoundary = notificationReplyRecoveryBoundary(System.currentTimeMillis())
            val persistedBoundary =
                withContext(Dispatchers.IO) {
                    completionStore.markStarted(completionKey, recoveryScope, recoveryBoundary)
                }
            if (persistedBoundary == null) return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
            while (!notificationReplySendWindowReady(persistedBoundary, System.currentTimeMillis())) {
                currentCoroutineContext().ensureActive()
                delay(NOTIFICATION_REPLY_SEND_WINDOW_POLL_MILLIS)
            }

            if (!appLock.notificationActionsAllowed) {
                return@withGroupCommitLock NotificationReplySendOutcome.BlockedByAppLock
            }
            val summary = marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, group, body) }
            // MDK assigns the app-event id before deciding whether this call
            // can publish it immediately. Persist it before returning either
            // outcome: after a process death, it is our durable proof that an
            // accepted-pending quick reply must never be sent a second time.
            val committedMessageId =
                summary.messageIds
                    .firstOrNull()
                    ?.takeIf { ConversationController.HEX_MESSAGE_ID.matches(it) }
                    ?: return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
            val committed =
                withContext(Dispatchers.IO) {
                    completionStore.markCommittedMessage(completionKey, committedMessageId)
                }
            if (!committed) return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
            if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                NotificationReplySendOutcome.AcceptedPending
            } else {
                NotificationReplySendOutcome.Sent
            }
        }
    }.onFailure {
        appStateDebug(it) {
            "notification reply failed for group=${group.take(REPLY_LOG_GROUP_LENGTH)}: ${it.readableMessage()}"
        }
    }.getOrElse(::notificationReplySendFailureOutcome)
}

/** Sends a notification quick reaction and reports MDK's accept disposition, not an assumed publication. */
internal suspend fun WhiteNoiseAppState.sendNotificationReaction(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    reaction: String,
): NotificationReactionSendOutcome {
    val emoji = normalizeNotificationReaction(reaction)
    val validTarget =
        accountRef.isNotBlank() &&
            groupIdHex.isNotBlank() &&
            ConversationController.HEX_MESSAGE_ID.matches(messageIdHex)
    if (!validTarget || emoji == null) {
        return NotificationReactionSendOutcome.NonRetryableFailure
    }
    return runCatchingCancellable {
        withGroupCommitLock(accountRef, groupIdHex) {
            if (!appLock.notificationActionsAllowed) {
                return@withGroupCommitLock NotificationReactionSendOutcome.BlockedByAppLock
            }
            val summary =
                marmotIo(MarmotTraceSection.MESSAGE_REACT) {
                    reactToMessage(accountRef, groupIdHex, messageIdHex, emoji)
                }
            notificationReactionOutcome(summary.acceptDisposition)
        }
    }.onFailure {
        appStateDebug(it) {
            "notification reaction failed for group=${groupIdHex.take(REPLY_LOG_GROUP_LENGTH)}: ${it.readableMessage()}"
        }
    }.getOrElse(::notificationReactionSendFailureOutcome)
}

/** Checks whether a notification reply already landed after the recovery boundary, so a retry never sends twice. */
private suspend fun WhiteNoiseAppState.notificationReplyCommitState(
    account: String,
    group: String,
    recoveryState: NotificationReplyRecoveryState,
    nextAttemptBoundary: NotificationReplyRecoveryBoundary?,
    text: String,
): NotificationReplyCommitProbe {
    if (!ConversationController.HEX_MESSAGE_ID.matches(recoveryState.boundary.messageIdHex)) {
        return NotificationReplyCommitProbe.Indeterminate
    }
    if (
        nextAttemptBoundary != null &&
        !ConversationController.HEX_MESSAGE_ID.matches(nextAttemptBoundary.messageIdHex)
    ) {
        return NotificationReplyCommitProbe.Indeterminate
    }
    return runCatchingCancellable {
        probeNotificationReplyCommit(
            recoveryState = recoveryState,
            nextAttemptBoundary = nextAttemptBoundary,
            text = text,
        ) { after, limit ->
            val page =
                marmotIo {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi.page(
                            groupIdHex = group,
                            search = null,
                            before = null,
                            beforeMessageId = null,
                            after = after.timelineAt,
                            afterMessageId = after.messageIdHex,
                            limit = limit,
                        ),
                    )
                }
            NotificationReplyTimelinePage(
                records =
                    page.messages.map { record ->
                        NotificationReplyTimelineRecord(
                            timelineAt = record.timelineAt,
                            messageIdHex = record.messageIdHex,
                            sourceMessageIdHex = record.sourceMessageIdHex,
                            direction = record.direction,
                            plaintext = record.plaintext,
                            recipientConfirmed = record.recipientConfirmed,
                        )
                    },
                hasMoreAfter = page.hasMoreAfter,
            )
        }
    }.onFailure {
        appStateDebug(it) {
            "notification reply dedupe probe failed for group=${group.take(REPLY_LOG_GROUP_LENGTH)}: " +
                it.readableMessage()
        }
    }.getOrDefault(NotificationReplyCommitProbe.Indeterminate)
}

suspend fun WhiteNoiseAppState.markNotificationMessageRead(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
): Boolean {
    val account = accountRef.takeIf { it.isNotBlank() } ?: return false
    val group = groupIdHex.takeIf { it.isNotBlank() } ?: return false
    val message = messageIdHex.takeIf { ConversationController.HEX_MESSAGE_ID.matches(it) } ?: return false
    return runCatchingCancellable {
        // markTimelineMessageRead advances the persisted cursor monotonically;
        // an old notification tap must not move the read marker backwards.
        if (!appLock.notificationActionsAllowed) return false
        val row = marmotIo { markTimelineMessageRead(account, group, message) }
        if (!applyChatListRowFromMarkRead(account, row)) {
            reconcileAccountUnreadAfterNotificationMarkRead(account, cursorAdvanced = row != null)
        }
        true
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMAppState", "notification mark read failed", it)
    }.getOrDefault(false)
}

/**
 * Reconcile a background [accountRef]'s per-account unread aggregate after
 * a successful notification-action mark-read whose authoritative row could
 * not fold into a bound [ChatsController]. Without this, the acting
 * account's avatar dot keeps presenting the stale pre-action count until an
 * unrelated refresh happens to land.
 *
 * When the read cursor provably advanced, the stored CONFIRMED value is
 * known-stale: retain the count but stop presenting it, so a failed refresh
 * degrades honestly instead of keeping a stale dot lit. Reconciliation then
 * reuses the same coalesced refresh path inbound notification updates use,
 * which publishes the authoritative post-action projection for exactly this
 * account and never touches other accounts' values.
 */
internal fun WhiteNoiseAppState.reconcileAccountUnreadAfterNotificationMarkRead(
    accountRef: String,
    cursorAdvanced: Boolean,
) {
    // The active account's projection is owned by its bound (or rebinding)
    // ChatsController and, on the notification-tap open path, by the
    // account-switch snapshot staging — both publish the authoritative
    // value themselves. Reconciling here would only add redundant refresh
    // traffic to the open path; the gap this closes is the account that has
    // no such owner: a background account acted on from the shade.
    if (accountRef == activeAccountRef) return
    if (cursorAdvanced) accountUnreadStore.markUnknown(accountRef)
    // Honor the same destructive-teardown suppression as inbound-update
    // maintenance: a wipe owns listener and scheduler restarts, and the
    // post-restore lifecycle refresh reconciles the retained value.
    if (networkNotificationRecoverySuppressed) return
    unreadRefreshScheduler.schedule(accountRef)
}
