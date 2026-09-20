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
    captureLayout: ((tailIndex: Int) -> ConversationTailLayout)? = null,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean =
    revealSentAtLiveTail(
        resolveTailIndex = {
            val renderedTimelineSize = controller.timeline.count { !MessageProjector.isEdit(it.record) }
            conversationTimelineTailListIndex(
                timelineSize = renderedTimelineSize,
                trailingRowCount = controller.conversationTrailingRowCount(renderedTimelineSize),
            ) ?: 0
        },
        captureLayout = captureLayout,
        awaitFrame = awaitFrame,
    )

/**
 * Testable send-reveal transaction whose tail resolver remains live while the
 * optimistic row and bottom input finish measuring.
 */
internal suspend fun ConversationScrollCoordinator.revealSentAtLiveTail(
    resolveTailIndex: () -> Int,
    captureLayout: ((tailIndex: Int) -> ConversationTailLayout)? = null,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    // Read the settled intent before the jump replaces it with its transient mode.
    val readerFollowsTail = isFollowingTail
    val revealed =
        programmaticJump(
            targetMessageId = null,
            reason = ConversationScrollReason.Send,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            if (readerFollowsTail) {
                awaitFrame()
                scrollToTail(resolveTailIndex())
            } else {
                animateScrollToTail(
                    index = resolveTailIndex(),
                    resolveIndex = { resolveTailIndex() },
                )
            }
        }
    if (!revealed || !readerFollowsTail || captureLayout == null) return revealed

    // Dictation completion can dismiss its controls after the optimistic row
    // has entered the list. Keep the accepted-send owner alive across that
    // bounded bottom-geometry transition so the final bubble settles above the
    // measured composer rather than behind a stale one-frame inset.
    settleTailAfterLayoutChange(
        resolveTailIndex = resolveTailIndex,
        captureLayout = { captureLayout(resolveTailIndex()) },
        reason = ConversationScrollReason.Send,
        maxSettleFrames = SEND_TAIL_LAYOUT_SETTLE_FRAMES,
        minimumSettleFrames = SEND_TAIL_MINIMUM_SETTLE_FRAMES,
        settleTrigger = ConversationTailSettleTrigger.BottomGeometry,
        requireTailClearance = true,
        awaitFrame = awaitFrame,
    )
    return revealed
}

private const val SEND_TAIL_LAYOUT_SETTLE_FRAMES = 24
private const val SEND_TAIL_MINIMUM_SETTLE_FRAMES = 8
