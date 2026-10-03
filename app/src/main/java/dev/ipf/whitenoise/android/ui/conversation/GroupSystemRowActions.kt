package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Reads native/controller state on demand and dispatches only actions owned by this mounted activity. */
internal class GroupSystemRowActions(
    val controller: ConversationController,
    val appState: WhiteNoiseAppState,
    val owner: GroupSystemReactionOwner,
    private val readOnly: () -> Boolean,
    private val item: () -> TimelineMessage,
) {
    val hidden: Boolean
        get() =
            item().projected?.deleted == true ||
                item().projected?.invalidationStatus != null ||
                MessageProjector.isDeleted(owner.messageId, controller.deletedMessageIds)
    val canReact: Boolean
        get() = !readOnly() && !hidden && currentGroupSystemReactionTarget(controller, owner) != null
    val canDelete: Boolean
        get() =
            !controller.group.pendingConfirmation &&
                !hidden &&
                owner.messageId.isNotBlank() &&
                controller.acceptsConversationActionOwner(owner.accountRef, owner.groupId)
    val tallies get() = if (hidden) emptyList() else controller.reactions[owner.messageId].orEmpty()

    /** Rechecks ownership both at dispatch and after the shared conflator/commit-lock waits. */
    fun react(emoji: String) {
        appState.launchMutation {
            if (canReact) {
                currentGroupSystemReactionTarget(controller, owner)?.let { target ->
                    controller.toggleReaction(emoji, target.record) {
                        canReact
                    }
                }
            }
        }
    }

    /** Preserves local hiding, rejecting a delayed menu callback after account/chat retirement. */
    fun deleteForMe() {
        appState.launchMutation {
            if (canDelete) controller.hideMessageForMe(owner.messageId)
        }
    }
}
