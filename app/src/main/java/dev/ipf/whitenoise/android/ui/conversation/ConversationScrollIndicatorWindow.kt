package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.ipf.whitenoise.android.ui.common.ReadingScrollPosition
import dev.ipf.whitenoise.android.ui.common.drawReadingScrollIndicator
import dev.ipf.whitenoise.android.ui.common.rememberReadingIndicatorAlpha

private val structuralIndicatorKeys =
    setOf(
        "conversation-load-error-bottom",
        "conversation-load-error-top",
        "older-messages-loading",
        "retention-history-boundary",
        "group-recovery",
        "top-spacer",
    )

internal data class ConversationScrollIndicatorWindow(
    val messageKeys: List<Any>,
    val firstMessageIndex: Int,
    val expectedItemCount: Int,
    val owner: Any,
)

private fun LazyListLayoutInfo.hasMeasuredIndicatorWindow(
    keys: List<Any>,
    firstIndex: Int,
    expectedItemCount: Int,
): Boolean {
    val hasRows =
        keys.isNotEmpty() &&
            totalItemsCount == expectedItemCount &&
            expectedItemCount >= keys.size + firstIndex
    val hasViewport = viewportSize.height > 0 && viewportEndOffset > viewportStartOffset
    val validDirection = reverseLayout && firstIndex >= 0
    return validDirection && hasRows && hasViewport
}

/** Continuous row coordinates within the retained window, including movement inside a tall row. */
internal fun conversationScrollPosition(
    layout: LazyListLayoutInfo,
    messageKeys: List<Any>,
    firstMessageIndex: Int,
    expectedItemCount: Int,
): ReadingScrollPosition? {
    if (!layout.hasMeasuredIndicatorWindow(messageKeys, firstMessageIndex, expectedItemCount)) return null
    val start = layout.viewportStartOffset.toFloat()
    val end = layout.viewportEndOffset.toFloat()
    var low = Float.POSITIVE_INFINITY
    var high = Float.NEGATIVE_INFINITY
    var valid = true
    for (row in layout.visibleItemsInfo) {
        val index = row.index - firstMessageIndex
        if (index in messageKeys.indices) {
            // Layout and the rendered window must agree before any old geometry can be painted.
            if (row.key == messageKeys[index] && row.size > 0) {
                val from = ((start - row.offset) / row.size).coerceIn(0f, 1f)
                val to = ((end - row.offset) / row.size).coerceIn(0f, 1f)
                if (to > from) {
                    low = minOf(low, index + from)
                    high = maxOf(high, index + to)
                }
            } else {
                valid = false
            }
        } else if (row.key !in structuralIndicatorKeys || row.index !in 0 until layout.totalItemsCount) {
            valid = false
        }
    }
    return if (valid) measuredWindowScrollPosition(low, high, messageKeys.size.toFloat()) else null
}

private fun measuredWindowScrollPosition(
    low: Float,
    high: Float,
    count: Float,
): ReadingScrollPosition? {
    val measured = low.isFinite() && high.isFinite()
    val visible = high - low
    if (!measured || visible <= 0f || visible >= count) return null
    return ReadingScrollPosition(
        progress = ((count - high) / (count - visible)).coerceIn(0f, 1f),
        visibleFraction = visible / count,
    )
}

/** Paints in the outer gutter, confined vertically to the measured clear native reading viewport. */
@Composable
internal fun Modifier.conversationScrollIndicator(
    state: LazyListState,
    viewport: ConversationTimelineViewport,
    window: ConversationScrollIndicatorWindow,
    enabled: Boolean,
): Modifier {
    var origin by remember(viewport) { mutableStateOf<Offset?>(null) }
    val alpha = rememberReadingIndicatorAlpha(window.owner to window.messageKeys, enabled) { state.isScrollInProgress }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return onGloballyPositioned { origin = it.positionInWindow() }.drawWithContent {
        drawContent()
        if (enabled && (state.canScrollBackward || state.canScrollForward)) {
            val bounds = viewport.readingBoundsInWindow
            val y = origin?.y
            if (bounds != null && y != null) {
                conversationScrollPosition(
                    viewport.readingLayoutInfo(),
                    window.messageKeys,
                    window.firstMessageIndex,
                    window.expectedItemCount,
                )?.let {
                    drawReadingScrollIndicator(it, color, alpha.value, bounds.top - y, bounds.height)
                }
            }
        }
    }
}
