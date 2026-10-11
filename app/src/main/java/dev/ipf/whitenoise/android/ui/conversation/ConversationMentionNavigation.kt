package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.state.tracedPagingSection

private const val MAX_MENTION_LAYOUT_CORRECTIONS = 3

/** Measured reverse-list coordinates, not a second timeline or protocol-state cache. */
internal data class ConversationMentionJumpLayout(
    val viewportEndOffsetPx: Int,
    val itemHeightPx: Int?,
    val estimatedItemHeightPx: Int? = null,
    val isNewest: Boolean = false,
    val itemOffsetPx: Int? = null,
    val tailContentHeightPx: Int? = null,
) {
    val isMeasured: Boolean
        get() = viewportEndOffsetPx > 0 && itemHeightPx != null && itemHeightPx > 0

    /** A requested offset alone cannot prove success when the native list clamps at its tail. */
    val isAtReadingStart: Boolean
        get() =
            itemOffsetPx == null ||
                (
                    isMeasured &&
                        kotlin.math.abs(itemOffsetPx + requireNotNull(itemHeightPx) - viewportEndOffsetPx) <= 1
                )

    val readingStartOffset: Int
        get() = ReplyNavigation.readingStartScrollOffset(viewportEndOffsetPx, itemHeightPx ?: estimatedItemHeightPx)
}

/** Includes measured newer rows, structural rows and spacing, even beneath composer occlusion. */
internal fun LazyListLayoutInfo.mentionTailContentHeightPx(index: Int): Int? {
    val origin = visibleItemsInfo.firstOrNull { it.index == 0 } ?: return null
    val target = visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    return (target.offset + target.size - origin.offset).coerceAtLeast(1)
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
            var initialIndex = resolveTargetIndex() ?: return@programmaticJump
            reserveMentionReadingSpace(initialIndex, readLayout, awaitLayout)
            initialIndex = resolveTargetIndex() ?: return@programmaticJump
            val initialOffset = readLayout(initialIndex).readingStartOffset
            var placedIndex = initialIndex
            var placedOffset = initialOffset
            val approached =
                tracedPagingSection(ConversationMentionJumpTrace.APPROACH) {
                    animateScrollToItem(
                        initialIndex,
                        initialOffset,
                        traceMentionJump = true,
                        resolveIndex = resolveTargetIndex,
                        resolveScrollOffset = { index ->
                            placedIndex = index
                            placedOffset = readLayout(index).readingStartOffset
                            placedOffset
                        },
                    )
                }
            if (!approached) return@programmaticJump

            tracedPagingSection(ConversationMentionJumpTrace.LAYOUT) { awaitLayout() }
            var measuredIndex = resolveTargetIndex() ?: return@programmaticJump
            var measuredLayout = readLayout(measuredIndex)
            // An expanded-row estimate can overshoot a now-collapsed row entirely.
            // Reach its newest edge once without the estimate, then measure afresh.
            if (!measuredLayout.isMeasured && measuredLayout.viewportEndOffsetPx > 0 && placedOffset != 0) {
                tracedPagingSection(ConversationMentionJumpTrace.CORRECTION) {
                    scrollToItem(measuredIndex, 0)
                }
                placedIndex = measuredIndex
                placedOffset = 0
                tracedPagingSection(ConversationMentionJumpTrace.LAYOUT) { awaitLayout() }
                measuredIndex = resolveTargetIndex() ?: return@programmaticJump
                measuredLayout = readLayout(measuredIndex)
            }
            if (!measuredLayout.isMeasured) return@programmaticJump
            reached =
                settleMentionStart(
                    resolveTargetIndex = resolveTargetIndex,
                    readLayout = readLayout,
                    awaitLayout = awaitLayout,
                    initialPlacedIndex = placedIndex,
                    initialPlacedOffset = placedOffset,
                )
        }
    if (completed) onCompleted()
    return completed && reached
}

/** A corrective write can remeasure an animating row; validate its fresh geometry before success. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.settleMentionStart(
    resolveTargetIndex: () -> Int?,
    readLayout: (Int) -> ConversationMentionJumpLayout,
    awaitLayout: suspend () -> Unit,
    initialPlacedIndex: Int,
    initialPlacedOffset: Int,
): Boolean {
    var placedIndex = initialPlacedIndex
    var placedOffset = initialPlacedOffset
    var attempt = 0
    var reached = false
    while (attempt <= MAX_MENTION_LAYOUT_CORRECTIONS && !reached) {
        val measured = readMentionLayoutAfterReserve(resolveTargetIndex, readLayout, awaitLayout) ?: break
        val (index, layout) = measured
        if (index == placedIndex && layout.readingStartOffset == placedOffset && layout.isAtReadingStart) {
            reached = true
        } else if (attempt < MAX_MENTION_LAYOUT_CORRECTIONS) {
            tracedPagingSection(ConversationMentionJumpTrace.CORRECTION) {
                scrollToItem(index, layout.readingStartOffset)
            }
            placedIndex = index
            placedOffset = layout.readingStartOffset
            tracedPagingSection(ConversationMentionJumpTrace.LAYOUT) { awaitLayout() }
        }
        attempt++
    }
    return reached
}

/** Reserves only the missing native runway; unknown near-tail geometry waits for the measured approach. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.reserveMentionReadingSpace(
    index: Int,
    readLayout: (Int) -> ConversationMentionJumpLayout,
    awaitLayout: suspend () -> Unit,
) {
    val layout = readLayout(index)
    val height =
        layout.tailContentHeightPx
            ?: if (layout.isNewest) layout.itemHeightPx ?: layout.estimatedItemHeightPx ?: 1 else null
    if (height != null) reserveMentionReadingStart(height, awaitLayout)
}

/** Re-resolves the logical target after a reserved-padding layout pass before validating its coordinate. */
private suspend fun ConversationScrollCoordinator.ConversationScrollCommandScope.readMentionLayoutAfterReserve(
    resolveTargetIndex: () -> Int?,
    readLayout: (Int) -> ConversationMentionJumpLayout,
    awaitLayout: suspend () -> Unit,
): Pair<Int, ConversationMentionJumpLayout>? {
    val measured = resolveMeasuredMentionLayout(resolveTargetIndex, readLayout) ?: return null
    val layout = measured.second
    val height = layout.tailContentHeightPx ?: layout.itemHeightPx.takeIf { layout.isNewest }
    return if (height != null) {
        reserveMentionReadingStart(height, awaitLayout)
        resolveMeasuredMentionLayout(resolveTargetIndex, readLayout)
    } else {
        measured
    }
}

/** Pairs the current logical index with measured geometry, rejecting targets absent from the viewport. */
private fun resolveMeasuredMentionLayout(
    resolveTargetIndex: () -> Int?,
    readLayout: (Int) -> ConversationMentionJumpLayout,
): Pair<Int, ConversationMentionJumpLayout>? =
    resolveTargetIndex()?.let { index ->
        readLayout(index).takeIf { it.isMeasured }?.let { index to it }
    }
