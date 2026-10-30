package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Rect
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
    private val onLocalIntent: (String, Boolean, Rect?) -> Unit = { _, _, _ -> },
) {
    val currentItem: TimelineMessage get() = item()
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

    /** Calls without a measured source preserve ordinary reaction acceptance. */
    fun react(emoji: String) {
        react(emoji, null)
    }

    /** Captures dispatch geometry before rechecking owners across mutation/commit-lock waits. */
    fun react(
        emoji: String,
        origin: Rect?,
    ) {
        appState.launchMutation {
            if (canReact) {
                currentGroupSystemReactionTarget(controller, owner)?.let { target ->
                    controller.toggleReaction(emoji, target.record, onLocalIntent = { added ->
                        onLocalIntent(emoji, added, origin)
                    }) {
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
