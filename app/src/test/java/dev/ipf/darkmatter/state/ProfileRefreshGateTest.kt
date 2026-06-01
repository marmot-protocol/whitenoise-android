package dev.ipf.darkmatter.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRefreshGateTest {
    private val gate = ProfileRefreshGate(retryCooldownMillis = 1_000L)

    @Test
    fun concurrentRefreshIsDeduplicated() {
        assertTrue(gate.tryStart("alice", nowMillis = 100L))
        assertFalse(gate.tryStart("alice", nowMillis = 100L))
    }

    @Test
    fun passiveRetryWaitsForCooldownAfterCompletion() {
        assertTrue(gate.tryStart("alice", nowMillis = 100L))
        gate.finish("alice", nowMillis = 200L)

        assertFalse(gate.tryStart("alice", nowMillis = 1_199L))
        assertTrue(gate.tryStart("alice", nowMillis = 1_200L))
    }

    @Test
    fun accountsAreThrottledIndependently() {
        assertTrue(gate.tryStart("alice", nowMillis = 100L))
        gate.finish("alice", nowMillis = 200L)

        assertTrue(gate.tryStart("bob", nowMillis = 201L))
    }

    @Test
    fun expiredCooldownEntriesAreEvictedLazily() {
        assertTrue(gate.tryStart("alice", nowMillis = 100L))
        gate.finish("alice", nowMillis = 200L)
        assertEquals(1, gate.retainedCooldownCount())

        assertTrue(gate.tryStart("bob", nowMillis = 1_200L))

        assertEquals(0, gate.retainedCooldownCount())
    }
}
