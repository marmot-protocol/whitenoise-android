package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRetryBackoffTest {

    @Test
    fun doublesEachStep() {
        assertEquals(2_000L, nextRetryBackoffMillis(1_000L, maxMillis = 60_000L))
        assertEquals(4_000L, nextRetryBackoffMillis(2_000L, maxMillis = 60_000L))
    }

    @Test
    fun capsAtMax() {
        // 40s * 2 = 80s, clamped to the 60s ceiling, and stays there.
        assertEquals(60_000L, nextRetryBackoffMillis(40_000L, maxMillis = 60_000L))
        assertEquals(60_000L, nextRetryBackoffMillis(60_000L, maxMillis = 60_000L))
    }
}
