package dev.ipf.whitenoise.android.ui.conversation

/**
 * A reversed transcript reduced to its measured geometry: index 0 is the newest row, a write places the first visible
 * row's newest edge at minus its scroll offset, and older rows stack above it. Like the real list's `scrollToItem`,
 * which forces a synchronous remeasure, a write is measured at once. Before the first write or [settle] nothing is
 * measured.
 */
internal class IncomingAppendTestList(
    private val viewportEndPx: Int,
    heightsPx: List<Int>,
) : ConversationScrollWriter {
    /** Row heights by list index, newest first — a test may grow one to model a late remeasure. */
    val heightsPx = heightsPx.toMutableList()

    /** Every write in order, as (index, scroll offset) pairs. */
    val writes = mutableListOf<Pair<Int, Int>>()

    /** How many frames had passed at each write in [writes], so a test can tell writes in one frame apart. */
    val writeFrames = mutableListOf<Int>()
    private var frames = 0
    private var requested = 0 to 0
    private var measured: Pair<Int, Int>? = null

    override val firstVisibleItemIndex: Int
        get() = requested.first

    /** Records a snap and measures it at once, as the real list's forced remeasure does. */
    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        writes += index to scrollOffset
        writeFrames += frames
        requested = index to scrollOffset
        measured = requested
    }

    /** Records an animated write the same way, since only its destination matters here. */
    override suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int,
    ) = scrollToItem(index, scrollOffset)

    /** Advances one frame, which changes no geometry here because every write is already measured. */
    fun frame() {
        frames++
    }

    /** Marks the current position as measured without a write, as an already-settled list is. */
    fun settle(
        index: Int = 0,
        offset: Int = 0,
    ) {
        requested = index to offset
        measured = requested
    }

    /** The measured geometry of [index] in the last layout pass, unmeasured while it is not laid out. */
    fun layout(index: Int): ConversationMentionJumpLayout {
        val (first, offset) = measured ?: return ConversationMentionJumpLayout(viewportEndPx, itemHeightPx = null)
        var start = -offset
        for (row in first until index) start += heightsPx[row]
        val laidOut = index >= first && index < heightsPx.size && start < viewportEndPx
        return ConversationMentionJumpLayout(
            viewportEndOffsetPx = viewportEndPx,
            itemHeightPx = heightsPx.getOrNull(index)?.takeIf { laidOut },
            itemOffsetPx = start.takeIf { laidOut },
        )
    }

    /** A probe that resolves [index] while the row exists and reads this list's measured geometry. */
    fun probe(index: () -> Int?): ConversationReadingStartProbe =
        ConversationReadingStartProbe(
            resolveTargetIndex = index,
            readLayout = ::layout,
            awaitLayout = { frame() },
            traceSections = false,
        )
}
