package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.castPollVote

/** Immutable identity captured by one mounted poll's action surface. */
internal data class PollMessageActionOwner(
    val accountRef: String?,
    val groupId: String,
    val messageId: String,
)

/** Consults the bounded current timeline again when dispatching a delayed menu/picker callback. */
internal fun currentPollActionTarget(
    controller: ConversationController,
    owner: PollMessageActionOwner,
    nowSeconds: ULong = (System.currentTimeMillis() / POLL_ACTION_MILLIS_PER_SECOND).toULong(),
): TimelineMessage? {
    val mayAct =
        pollOwnerMayAct(controller, owner) &&
            !MessageProjector.isDeleted(owner.messageId, controller.deletedMessageIds)
    return if (mayAct) {
        val current =
            controller.timelineItemsById[owner.messageId]
                ?: controller.timeline.firstOrNull { it.record.messageIdHex == owner.messageId }
        current?.takeIf { pollMessageActionsEligible(it, owner, nowSeconds) }
    } else {
        null
    }
}

private fun pollOwnerMayAct(
    controller: ConversationController,
    owner: PollMessageActionOwner,
): Boolean =
    owner.accountRef != null &&
        controller.acceptsConversationActionOwner(owner.accountRef, owner.groupId) &&
        pollConversationMayAct(controller)

private fun pollConversationMayAct(controller: ConversationController): Boolean {
    return controller.canSendMessages && !controller.group.pendingConfirmation
}

/** The poll's vote deadline deliberately has no part in message discussion eligibility. */
internal fun pollMessageActionsEligible(
    item: TimelineMessage,
    owner: PollMessageActionOwner,
    nowSeconds: ULong,
): Boolean {
    val projected = item.projected ?: return false
    val matchesTarget =
        item.record.messageIdHex.isNotBlank() &&
            item.record.messageIdHex == owner.messageId &&
            item.record.groupIdHex == owner.groupId
    val livePoll = MessageProjector.isPollKind(item.record.kind) && projected.poll != null && !projected.deleted
    val retained =
        projected.invalidationStatus == null && (item.record.retentionExpiresAt?.let { it > nowSeconds } ?: true)
    // Native poll projections already carry a stable target while relay publication is pending.
    val available =
        item.status == MessageStatus.Sent ||
            item.status == MessageStatus.Received ||
            item.status == MessageStatus.Pending
    return if (!matchesTarget || !livePoll || !retained) false else available
}

private const val POLL_ACTION_MILLIS_PER_SECOND = 1_000L

/** A completion can only update the poll surface that admitted the vote. */
internal suspend fun submitOwnedPollVote(
    controller: ConversationController,
    owner: PollMessageActionOwner,
    replacement: List<String>,
    onCompleted: (SendAcceptDispositionFfi?) -> Unit,
) {
    var outcome: SendAcceptDispositionFfi? = null
    try {
        if (currentPollActionTarget(controller, owner) != null) {
            outcome = controller.castPollVote(owner.messageId, replacement)
        }
    } finally {
        if (controller.acceptsConversationActionOwner(owner.accountRef, owner.groupId)) {
            onCompleted(outcome)
        }
    }
}
