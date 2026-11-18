package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Index-mapping invariants for the reversed conversation list.
 *
 * Structural rows such as the group-recovery card (#2621) are emitted above the
 * timeline, at the high-index end of the reversed list, so the newest row and
 * every tail-relative timeline mapping must stay put whatever sits above them.
 */
class ConversationGroupRecoveryRowIndexTest {
    /**
     * Leaves the newest row at the reversed list's origin whatever sits above it.
     *
     * Structural rows are emitted above the timeline, so in the reversed list
     * they occupy the high-index end and cannot shift the newest row.
     */
    @Test
    fun tailRowIsUnaffectedByLeadingStructuralRows() {
        assertEquals(
            0,
            conversationTimelineTailListIndex(timelineSize = 4, trailingRowCount = 0),
        )
        assertEquals(
            1,
            conversationTimelineTailListIndex(timelineSize = 4, trailingRowCount = 1),
        )
    }

    /** Leaves an empty timeline without a tail row. */
    @Test
    fun emptyTimelineHasNoTailRow() {
        assertEquals(null, conversationTimelineTailListIndex(timelineSize = 0, trailingRowCount = 1))
    }

    /** Places the newest message at the origin and the oldest furthest from it. */
    @Test
    fun listIndexMappingInvertsChronologicalOrder() {
        val size = 5
        assertEquals(0, conversationTimelineListIndex(timelineIndex = 4, timelineSize = size, trailingRowCount = 0))
        assertEquals(4, conversationTimelineListIndex(timelineIndex = 0, timelineSize = size, trailingRowCount = 0))
        assertEquals(1, conversationTimelineListIndex(timelineIndex = 4, timelineSize = size, trailingRowCount = 1))
    }

    /** Round-trips every chronological position back through the reverse mapping. */
    @Test
    fun listIndexMappingRoundTrips() {
        val size = 7
        for (trailing in 0..1) {
            for (timelineIndex in 0 until size) {
                val listIndex =
                    conversationTimelineListIndex(timelineIndex, size, trailing)
                assertEquals(
                    timelineIndex,
                    conversationTimelineIndexForListIndex(listIndex, size, trailing),
                )
            }
        }
    }
}
