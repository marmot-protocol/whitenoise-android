package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.ReplyNavigation

/** Measured reverse-list coordinates, not a second timeline or protocol-state cache. */
internal data class ConversationMentionJumpLayout(
    val viewportEndOffsetPx: Int,
    val itemHeightPx: Int?,
    val estimatedItemHeightPx: Int? = null,
) {
    val isMeasured: Boolean
        get() = viewportEndOffsetPx > 0 && itemHeightPx != null && itemHeightPx > 0

    val readingStartOffset: Int
        get() = ReplyNavigation.readingStartScrollOffset(viewportEndOffsetPx, itemHeightPx ?: estimatedItemHeightPx)
}

/** Keeps the approach and measured correction in one latest-wins, distance-bounded command. */
internal suspend fun ConversationScrollCoordinator.jumpToMentionReadingStart(
    targetMessageId: String,
    resolveTargetIndex: () -> Int?,
    readLayout: (Int) -> ConversationMentionJumpLayout,
    awaitLayout: suspend () -> Unit = { withFrameNanos { } },
    onCompleted: () -> Unit = {},
): Boolean {
    var reached = false
    val completed =
        programmaticJump(targetMessageId, ConversationScrollReason.Mention) {
            val initialIndex = resolveTargetIndex() ?: return@programmaticJump
            val initialOffset = readLayout(initialIndex).readingStartOffset
            if (!animateScrollToItem(initialIndex, initialOffset, resolveTargetIndex)) return@programmaticJump

            awaitLayout()
            var placedIndex = initialIndex
            var placedOffset = initialOffset
            var measuredIndex = resolveTargetIndex() ?: return@programmaticJump
            var measuredLayout = readLayout(measuredIndex)
            // An expanded-row estimate can overshoot a now-collapsed row entirely.
            // Reach its newest edge once without the estimate, then measure afresh.
            if (!measuredLayout.isMeasured && measuredLayout.viewportEndOffsetPx > 0 && initialOffset != 0) {
                scrollToItem(measuredIndex, 0)
                placedIndex = measuredIndex
                placedOffset = 0
                awaitLayout()
                measuredIndex = resolveTargetIndex() ?: return@programmaticJump
                measuredLayout = readLayout(measuredIndex)
            }
            if (!measuredLayout.isMeasured) return@programmaticJump
            if (measuredIndex != placedIndex || measuredLayout.readingStartOffset != placedOffset) {
                scrollToItem(measuredIndex, measuredLayout.readingStartOffset)
            }
            if (resolveTargetIndex() != measuredIndex) return@programmaticJump
            reached = true
        }
    if (completed) onCompleted()
    return completed && reached
}
