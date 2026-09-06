package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController

/**
 * Reveals the latest rendered row only after the controller has published the
 * optimistic send. The resolver stays live through a far-target approach so a
 * concurrent projection cannot leave the newly sent row below the viewport.
 */
internal suspend fun ConversationScrollCoordinator.revealSentAtLiveTail(controller: ConversationController): Boolean {
    /** Maps the controller's newest non-edit projection to its current lazy-list row. */
    fun liveTailIndex(): Int {
        val renderedTimelineSize = controller.timeline.count { !MessageProjector.isEdit(it.record) }
        return conversationTimelineTailListIndex(
            timelineSize = renderedTimelineSize,
            leadingStructuralRowCount = controller.conversationLeadingStructuralRowCount(renderedTimelineSize),
        ) ?: 0
    }

    return programmaticJump(
        targetMessageId = null,
        reason = ConversationScrollReason.Send,
        resultingMode = ConversationScrollMode.FollowingTail,
    ) {
        animateScrollToTail(
            index = liveTailIndex(),
            resolveIndex = ::liveTailIndex,
        )
    }
}
