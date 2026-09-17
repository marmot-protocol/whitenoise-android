package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the group-recovery card's list row (#2621).
 *
 * The transcript emits that card as a lazy row above the timeline whenever the
 * conversation carries visible recovery state, but the structural-row count
 * used to omit it. Every timeline-index to list-index mapping was therefore one
 * row short while the card was on screen, which anchored scroll restores,
 * read receipts and the tail follower to the wrong message.
 */
class ConversationGroupRecoveryRowIndexTest {
    /** Counts the recovery row alongside the older header and the inline top error. */
    @Test
    fun structuralRowCountIncludesTheGroupRecoveryRow() {
        assertEquals(
            0,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = false,
                hasInlineTopError = false,
                hasGroupRecovery = false,
            ),
        )
        assertEquals(
            1,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = false,
                hasInlineTopError = false,
                hasGroupRecovery = true,
            ),
        )
        assertEquals(
            3,
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
                hasGroupRecovery = true,
            ),
        )
    }

    /** Keeps the historical count unchanged for callers that render no recovery row. */
    @Test
    fun structuralRowCountDefaultsToTheRecoveryFreeLayout() {
        assertEquals(
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
            ),
            conversationTimelineLeadingStructuralRowCount(
                hasOlderHeader = true,
                hasInlineTopError = true,
                hasGroupRecovery = false,
            ),
        )
    }

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
