package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.exceedsOutgoingMessageLimit
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.CancellationException

/** The optimistic row a first text send created, carried into admission, reconciliation and failure handling. */
internal class OptimisticTextSendRow(
    val key: String,
    val tempId: String,
    val record: AppMessageRecordFfi,
    val order: ULong,
    val chatListPreviewReserved: Boolean,
)

/**
 * The account a composer text send runs as, or null when a guard refuses it. Every guard runs before any
 * optimistic bubble exists, so a refused send keeps the composer's text.
 */
internal fun ConversationController.textSendAccount(
    text: String,
    trimmed: String,
): String? {
    val accountRef = conversationAccountRef
    return when {
        showsBlockedLeave -> {
            // Dictation's send action and the in-conversation wave and location shares reach here without the
            // composer gate, so say why.
            if (text.isNotBlank()) appState.presentTransient(blockedLeaveHint(), kind = TransientNoticeKind.Information)
            null
        }
        exceedsOutgoingMessageLimit(trimmed) -> {
            // Sends and edits both stop here, before any optimistic bubble, so the composer keeps its text.
            appState.present(R.string.toast_message_too_long)
            null
        }
        !canAcceptTextSend(
            accountRef = accountRef,
            trimmed = trimmed,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfMembership.selfLeft,
            unrecoverable = group.unrecoverable,
            disbanding = group.disbanding,
            disbanded = group.disbanded,
        ) -> {
            presentMembershipVerifyingNotice(accountRef, trimmed)
            null
        }
        // Non-null guaranteed by canAcceptTextSend above.
        else -> requireNotNull(accountRef)
    }
}

/**
 * A positive seeded member is accepted during refresh. A visible composer can still reach the send guard from a
 * genuinely unknown notification-open state; preserve the draft and surface that the handoff has not started
 * instead of dropping it silently.
 */
private fun ConversationController.presentMembershipVerifyingNotice(
    accountRef: String?,
    trimmed: String,
) {
    val sendHasContent = accountRef != null && trimmed.isNotEmpty()
    val membershipUnknown = !membersVerified && !seededSelfMember && !selfMembership.selfLeft
    val groupCanEventuallySend = !group.unrecoverable && !group.disbanding && !group.disbanded
    if (sendHasContent && membershipUnknown && groupCanEventuallySend) {
        appState.present(R.string.toast_send_membership_verifying)
    }
}

/**
 * Inserts and publishes the Pending bubble for a new text send with client token [tempId], registers its
 * durable-acceptance callback and reserves its chat-list position.
 */
internal fun ConversationController.insertOptimisticTextSend(
    tempId: String,
    trimmed: String,
    replyTarget: String?,
    onDurablyAccepted: () -> Unit,
): OptimisticTextSendRow {
    val now = nowSeconds()
    val retentionAtSendSeconds = rememberRetentionAtSend(tempId, group.disappearingMessageSecs)
    val optimistic = optimisticTextRecord(tempId, trimmed, replyTarget, now)
    val optimisticOrder = nextOptimisticTimelineOrder()
    val optimisticKey = "msg:$tempId"
    optimisticMessages[optimisticKey] =
        TimelineMessage(
            optimisticKey,
            optimistic,
            MessageStatus.Pending,
            timelineOrder = optimisticOrder,
            retentionAtSendSeconds = retentionAtSendSeconds,
        )
    optimisticSendPhases[optimisticKey] = OptimisticSendPhase.PRE_ACCEPTANCE
    durableAcceptanceCallbacks[optimisticKey] = onDurablyAccepted
    messageById[tempId] = optimistic
    val chatListPreviewReserved =
        appState.reserveOptimisticSentPreview(
            conversationAccountRef,
            group.groupIdHex,
            tempId,
        )
    publishTimelineFromIndexes()
    return OptimisticTextSendRow(optimisticKey, tempId, optimistic, optimisticOrder, chatListPreviewReserved)
}

/** The record a new text send's bubble shows until MDK's projection replaces it. */
private fun ConversationController.optimisticTextRecord(
    tempId: String,
    trimmed: String,
    replyTarget: String?,
    now: ULong,
): AppMessageRecordFfi =
    AppMessageRecordFfi(
        invalidated = false,
        messageIdHex = tempId,
        direction = "sent",
        groupIdHex = group.groupIdHex,
        sender = conversationAccountIdHex ?: "",
        plaintext = trimmed,
        // Publish with an empty AST so the bubble reaches the very next
        // frame without first suspending on the parse FFI's IO hop — a
        // congested IO lane used to hold the whole bubble hostage. An
        // empty document renders the plaintext unstyled;
        // hydrateOptimisticSendMarkdown below rebinds the styled
        // document the projected record will carry.
        contentTokens = EMPTY_MARKDOWN_DOCUMENT,
        kind = 9uL,
        tags =
            replyTarget
                ?.let {
                    listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it))
                }.orEmpty(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = now,
        receivedAt = now,
        sticker = null,
    )

/**
 * Publishes the reserved chat-list preview with the bubble's completed [presented] document. Keep the prior
 * chat-list row while the parser is in flight. The optimistic preview is published only with a completed document,
 * never as a raw frame followed by a styled replacement (#2411). An empty/failed parse publishes one stable
 * plaintext fallback only after the attempt completes. This step does not suspend: it checks cancellation and
 * applies on this controller's context, so a cancel cannot interleave, and a native publish holding the commit lock
 * cannot delay styling.
 */
internal fun ConversationController.applyOptimisticSendPreview(
    row: OptimisticTextSendRow,
    presented: AppMessageRecordFfi,
    trace: PerformanceTrace?,
) {
    requireOptimisticSendNotCancelled(row.key)
    val previewApplied =
        row.chatListPreviewReserved &&
            appState.applyReservedOptimisticSentPreview(
                conversationAccountRef,
                group.groupIdHex,
                sentPreview(row.tempId, row.record.plaintext, presented.contentTokens, row.record.recordedAt),
            )
    if (!previewApplied) {
        // No bound row to bump (pre-first-frame open, brand-new group,
        // account-pinned window) — the engine echo will still update the
        // list, but keep the drop visible in the send trace.
        sendTrace(trace, PerformancePhase.CHAT_LIST_PREVIEW_DROPPED, result = PerformanceResult.FAILURE)
    }
}

/**
 * Settles a first text send whose admission or presentation threw. Cancellation rethrows after its cleanup;
 * every other outcome is final for this attempt. [presented] is the bubble's latest record.
 */
@Suppress("LongParameterList") // The host attempt and caller callback stay with send's own lifetime.
internal fun ConversationController.handleTextSendFailure(
    throwable: Throwable,
    row: OptimisticTextSendRow,
    presented: AppMessageRecordFfi,
    account: String,
    trace: PerformanceTrace?,
    sendHostAttempt: HostPerformanceAttempt,
    onTerminalFailure: () -> Unit,
) {
    if (throwable is OptimisticSendCancelledException) {
        sendHostAttempt.cancel()
        discardCancelledSendAttempt(row.key, row.tempId)
        onTerminalFailure()
        return
    }
    retireCancelledSendTombstone(throwable, row.key)
    if (throwable is CancellationException) sendHostAttempt.cancel()
    throwable.rethrowIfCancellation()
    when {
        throwable.isUseAfterEviction() -> {
            sendHostAttempt.failure()
            // The engine realized our own eviction while replaying to send:
            // we are no longer a member, so this message can never publish.
            // Drop the optimistic bubble (a retry would only re-fail) and
            // flip to the read-only removed state — the same realization the
            // conversation-open and refresh paths perform — instead of
            // surfacing the raw backend error.
            discardOptimisticSend(row.key, row.tempId, OptimisticSendDiscard.ABANDONED)
            markActiveAccountRemovedFromMembers(account)
            onTerminalFailure()
        }
        isAmbiguousRelayDeliveryError(throwable) -> {
            sendHostAttempt.unavailable()
            // The event may already be on a relay. Preserve both the
            // optimistic bubble and chat-list preview as Pending, then let
            // an authoritative projection or MDK convergence settle it.
            // Calling textPublisher again here could mint a duplicate.
            markTextDeliveryUncertain(row.key, row.tempId, trace)
            Log.w(
                "ConversationController",
                "message delivery uncertain; keeping pending type=${throwable.javaClass.simpleName}",
            )
            publishTimelineFromIndexes()
        }
        optimisticSendIsMdkOwnedOrUnknown(row.key) -> {
            sendHostAttempt.unavailable()
            publishTimelineFromIndexes()
        }
        else -> failTextSend(throwable, row, presented, trace, sendHostAttempt, onTerminalFailure)
    }
}

/** Keeps a definitely failed first send visible as a Failed row and reports the failure. */
@Suppress("LongParameterList") // The host attempt and caller callback stay with send's own lifetime.
private fun ConversationController.failTextSend(
    throwable: Throwable,
    row: OptimisticTextSendRow,
    presented: AppMessageRecordFfi,
    trace: PerformanceTrace?,
    sendHostAttempt: HostPerformanceAttempt,
    onTerminalFailure: () -> Unit,
) {
    // The bubble stays visible as Failed — the row must agree instead
    // of silently reverting to the prior message.
    failOptimisticChatListPreview(row.tempId)
    retainFailedOptimisticTextSend(
        optimisticMessages = optimisticMessages,
        messageById = messageById,
        key = row.key,
        optimistic = presented,
        timelineOrder = row.order,
    )
    optimisticSendPhases[row.key] = OptimisticSendPhase.FAILED
    suppressUnpublishedCopiesOf(presented)
    publishTimelineFromIndexes()
    sendTrace(
        trace,
        PerformancePhase.SEND_FAILED,
        result = PerformanceResult.FAILURE,
    )
    appState.pendingSendDiagnostics.forget(row.tempId)
    sendHostAttempt.failure()
    presentSendFailure(appState, throwable, sendFailureAttempt(row.key))
    onTerminalFailure()
}
