package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState

internal interface ConversationScrollWriter {
    val firstVisibleItemIndex: Int

    suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )

    suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )

    /** Reaches the physical end of [index], including when that item exceeds the viewport. */
    suspend fun scrollToTail(index: Int) {
        scrollToItem(index)
    }

    /** Animated counterpart of [scrollToTail]. */
    suspend fun animateScrollToTail(index: Int) {
        animateScrollToItem(index)
    }

    /**
     * Corrects an already-reached [index] so its reading start, not its newest
     * edge, meets the top of the viewport. A no-op unless the row overhangs.
     */
    suspend fun alignReadingStart(index: Int) = Unit
}

/** The only implementation that writes the Compose list state. */
internal class LazyListConversationScrollWriter(
    private val listState: LazyListState,
    private val evidenceSink: ConversationScrollEvidenceSink? = null,
) : ConversationScrollWriter {
    override val firstVisibleItemIndex: Int
        get() = listState.firstVisibleItemIndex

    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        evidenceSink?.onWrite(
            ConversationScrollWriteEvidence(
                animated = false,
                index = index,
                offsetPx = scrollOffset,
            ),
        )
        listState.scrollToItem(index, scrollOffset)
    }

    override suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        evidenceSink?.onWrite(
            ConversationScrollWriteEvidence(
                animated = true,
                index = index,
                offsetPx = scrollOffset,
            ),
        )
        listState.animateScrollToItem(index, scrollOffset)
    }

    /**
     * Reaches the physical newest edge of [index] in one write.
     *
     * The transcript is reversed, so a zero scroll offset already lands the
     * row's newest edge against the composer however tall the row is. The old
     * forward layout needed a measured second pass to scroll through an
     * oversized final row; that no longer has anything to correct.
     */
    override suspend fun scrollToTail(index: Int) {
        scrollToItem(index, 0)
    }

    /** Animates to the physical newest edge of [index]; see [scrollToTail]. */
    override suspend fun animateScrollToTail(index: Int) {
        animateScrollToItem(index, 0)
    }

    /**
     * The reversed transcript anchors rows by their newest edge, so a zero offset
     * lands a row's end against the composer. A row taller than the viewport must
     * therefore be scrolled through by its overhang for its reading start to reach
     * the top. The caller has already reached the row, so its height is measured.
     */
    override suspend fun alignReadingStart(index: Int) {
        val offset = readingStartOffset(index)
        if (offset > 0) animateScrollToItem(index, offset)
    }

    /**
     * The overhang a row has beyond the viewport's far edge, which is what separates
     * its two edges. A scroll offset places the row's own edge at minus that offset,
     * so the row's reading start meets [LazyListLayoutInfo.viewportEndOffset] exactly
     * when the offset is its height less that edge.
     */
    private fun readingStartOffset(index: Int): Int {
        val layoutInfo = listState.layoutInfo
        val size = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: return 0
        return (size - layoutInfo.viewportEndOffset).coerceAtLeast(0)
    }
}
