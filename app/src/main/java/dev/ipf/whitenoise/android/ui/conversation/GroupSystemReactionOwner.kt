package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Captures the account, conversation and native row addressed by a mounted activity surface. */
internal data class GroupSystemReactionOwner(
    val accountRef: String?,
    val groupId: String,
    val messageId: String,
)

/** Rechecks native projection and conversation ownership before a delayed reaction reaches MDK. */
internal fun currentGroupSystemReactionTarget(
    controller: ConversationController,
    owner: GroupSystemReactionOwner,
): TimelineMessage? {
    val available =
        controller.acceptsConversationActionOwner(owner.accountRef, owner.groupId) &&
            controller.canSendMessages &&
            !controller.group.pendingConfirmation &&
            !MessageProjector.isDeleted(owner.messageId, controller.deletedMessageIds)
    if (!available) return null
    // A display snapshot can outlive removal from the retained window during frame settlement.
    val current = controller.retainedTimelineItem(owner.messageId) ?: return null
    return current.takeIf { groupSystemReactionEligible(it, owner) }
}

/** Only MDK's attributed, authenticated activity projection supplies a reaction target. */
internal fun groupSystemReactionEligible(
    item: TimelineMessage,
    owner: GroupSystemReactionOwner,
): Boolean {
    val projected = item.projected
    val activity = projected?.groupSystem ?: return false
    return owner.accountRef != null &&
        owner.messageId.isNotBlank() &&
        item.record.messageIdHex == owner.messageId &&
        item.record.groupIdHex == owner.groupId &&
        MessageProjector.isGroupSystem(item.record) &&
        item.record.direction == "system" &&
        !projected.deleted &&
        projected.invalidationStatus == null &&
        activity.provenance == GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE &&
        !activity.actorAccountIdHex.isNullOrBlank()
}
