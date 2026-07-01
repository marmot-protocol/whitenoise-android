package dev.ipf.whitenoise.android.state

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

    @Test
    fun doesNotOverflowAtExtremes() {
        // Doubling Long.MAX_VALUE overflows negative; must still clamp to max.
        assertEquals(60_000L, nextRetryBackoffMillis(Long.MAX_VALUE, maxMillis = 60_000L))
        assertEquals(Long.MAX_VALUE, nextRetryBackoffMillis(Long.MAX_VALUE - 1, maxMillis = Long.MAX_VALUE))
    }

    @Test
    fun nonPositiveCurrentStillAdvances() {
        assertEquals(2L, nextRetryBackoffMillis(0L, maxMillis = 60_000L))
        assertEquals(2L, nextRetryBackoffMillis(-5L, maxMillis = 60_000L))
    }
}
