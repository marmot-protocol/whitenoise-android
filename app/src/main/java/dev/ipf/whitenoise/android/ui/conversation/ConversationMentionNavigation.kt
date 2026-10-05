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
): Boolean {
    var reached = false
    val completed =
        programmaticJump(targetMessageId, ConversationScrollReason.Mention) {
            val initialIndex = resolveTargetIndex() ?: return@programmaticJump
            val initialOffset = readLayout(initialIndex).readingStartOffset
            if (!animateScrollToItem(initialIndex, initialOffset, resolveTargetIndex)) return@programmaticJump

            awaitLayout()
            val measuredIndex = resolveTargetIndex() ?: return@programmaticJump
            val measuredLayout = readLayout(measuredIndex)
            if (!measuredLayout.isMeasured) return@programmaticJump
            if (measuredIndex != initialIndex || measuredLayout.readingStartOffset != initialOffset) {
                scrollToItem(measuredIndex, measuredLayout.readingStartOffset)
            }
            reached = true
        }
    return completed && reached
}
