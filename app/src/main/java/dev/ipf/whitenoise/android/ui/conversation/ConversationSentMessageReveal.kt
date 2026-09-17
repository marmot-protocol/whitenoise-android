package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController

/**
 * Reveals the latest rendered row only after the controller has published the
 * optimistic send.
 *
 * A reader already following the tail gets the same treatment as an incoming
 * message: wait for the frame that measures the new row, then pin the list's
 * origin. Animating there would glide across the lines the composer released
 * when it emptied, which reads as the bubble blinking after the transcript was reversed in #2627. A reader up in
 * history is carried to the new row instead, and the resolver stays live through
 * that far-target approach so a concurrent projection cannot leave the newly
 * sent row below the viewport.
 */
internal suspend fun ConversationScrollCoordinator.revealSentAtLiveTail(
    controller: ConversationController,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    /** Maps the controller's newest non-edit projection to its current lazy-list row. */
    fun liveTailIndex(): Int {
        val renderedTimelineSize = controller.timeline.count { !MessageProjector.isEdit(it.record) }
        return conversationTimelineTailListIndex(
            timelineSize = renderedTimelineSize,
            trailingRowCount = controller.conversationTrailingRowCount(renderedTimelineSize),
        ) ?: 0
    }

    // Read the settled intent before the jump replaces it with its transient mode.
    val readerFollowsTail = isFollowingTail
    return programmaticJump(
        targetMessageId = null,
        reason = ConversationScrollReason.Send,
        resultingMode = ConversationScrollMode.FollowingTail,
    ) {
        if (readerFollowsTail) {
            awaitFrame()
            scrollToTail(liveTailIndex())
        } else {
            animateScrollToTail(
                index = liveTailIndex(),
                resolveIndex = ::liveTailIndex,
            )
        }
    }
}
