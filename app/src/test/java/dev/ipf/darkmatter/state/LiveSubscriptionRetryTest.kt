package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSubscriptionRetryTest {
    @Test
    fun doublesEachStepUntilCap() {
        assertEquals(1_000L, nextLiveSubscriptionRetryDelayMillis(500L))
        assertEquals(2_000L, nextLiveSubscriptionRetryDelayMillis(1_000L))
        assertEquals(4_000L, nextLiveSubscriptionRetryDelayMillis(2_000L))
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(4_000L))
    }

    @Test
    fun capsAtMaximum() {
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(8_000L))
        assertEquals(8_000L, nextLiveSubscriptionRetryDelayMillis(16_000L))
    }

    @Test
    fun accountScopedRetryStopsWhenControllerUnbinds() {
        assertEquals(true, shouldRetryLiveSubscriptionForAccount("alice", "alice"))
        assertEquals(false, shouldRetryLiveSubscriptionForAccount("alice", null))
        assertEquals(false, shouldRetryLiveSubscriptionForAccount("alice", "bob"))
    }
}
