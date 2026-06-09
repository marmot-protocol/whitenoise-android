package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test

class RetriedTimelineOrderTest {
    @Test
    fun reusesStoredOrderWhenSet() {
        assertEquals(42uL, retriedTimelineOrder(42uL) { error("should not be called") })
    }

    @Test
    fun fallsBackToFreshOrderWhenOrderIsUnsetSentinel() {
        // 0uL is the TimelineMessage default ("unset"); only then do we mint a
        // fresh order. Regression for #101 where the dead `?:` on a non-nullable
        // ULong never reached this fallback.
        assertEquals(7uL, retriedTimelineOrder(0uL) { 7uL })
    }
}
