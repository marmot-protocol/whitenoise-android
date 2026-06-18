package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test

class NextTimelineOrderTest {
    @Test
    fun emptyStartsAtOne() {
        assertEquals(1uL, nextTimelineOrder(emptySequence(), emptySequence()))
    }

    @Test
    fun onePastThePublishedMax() {
        assertEquals(6uL, nextTimelineOrder(sequenceOf(3uL, 5uL), emptySequence()))
    }

    @Test
    fun onePastAPendingOnlyMax() {
        assertEquals(4uL, nextTimelineOrder(emptySequence(), sequenceOf(3uL)))
    }

    @Test
    fun inFlightOptimisticItemsAvoidBackToBackCollision() {
        // The published timeline is stale at 5 while a queued optimistic item
        // already holds 6. The next order must be 7, not a second 6 — if
        // `pending` were ignored this would regress to 6.
        assertEquals(7uL, nextTimelineOrder(sequenceOf(5uL), sequenceOf(6uL)))
    }
}
