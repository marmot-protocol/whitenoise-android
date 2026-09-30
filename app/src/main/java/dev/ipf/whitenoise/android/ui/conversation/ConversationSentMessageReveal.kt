package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationPagingOrigin
import dev.ipf.whitenoise.android.state.returnToLatestWindow
import kotlinx.coroutines.CancellationException

/**
 * Reveals the latest rendered row only after the controller has published the
 * optimistic send.
 *
 * Every accepted send snaps to the physical tail, including sends from older
 * history. Prepare the authoritative newest window before resolving its row,
 * then keep resolving that live tail while the bottom input finishes measuring.
 * A newer gesture or navigation still cancels the coordinator-owned transaction.
 */
internal suspend fun ConversationScrollCoordinator.revealSentAtLiveTail(
    controller: ConversationController,
    captureLayout: ((tailIndex: Int) -> ConversationTailLayout)? = null,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean =
    revealSentAtLiveTail(
        prepareLatest = {
            !controller.hasMoreAfterTimeline ||
                loadConversationTimelineToNewest(
                    hasMoreAfter = { controller.hasMoreAfterTimeline },
                    // The reveal follows a send rather than a request for newer pages, so a window the
                    // engine cannot answer recovers quietly instead of reporting a paging failure (#2764).
                    loadNewer = { controller.loadNewerTimelinePage(ConversationPagingOrigin.AUTOMATIC) },
                    returnToLatest = controller::returnToLatestWindow,
                )
        },
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
    prepareLatest: suspend () -> Boolean = { true },
    resolveTailIndex: () -> Int,
    captureLayout: ((tailIndex: Int) -> ConversationTailLayout)? = null,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    val revealed =
        programmaticJump(
            targetMessageId = null,
            reason = ConversationScrollReason.Send,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            if (!prepareLatest()) {
                throw CancellationException("Conversation newest edge was not available after send")
            }
            // The controller replacement is synchronous, but the reversed
            // LazyColumn needs one frame to install that newest window before
            // index zero denotes the physical conversation tail.
            awaitFrame()
            scrollToTail(resolveTailIndex())
        }
    if (!revealed || captureLayout == null) return revealed

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
