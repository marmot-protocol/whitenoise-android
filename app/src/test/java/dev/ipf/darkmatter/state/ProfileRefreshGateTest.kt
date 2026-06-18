package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun quiescentGatePrunesExpiredCooldownsOnFinish() {
        // A burst of distinct senders finishing, with no intervening
        // tryStart, must not accumulate one never-evicted cooldown entry per
        // pubkey for the process lifetime (#230). finish() sweeps elapsed
        // cooldowns, so the retained set tracks only *live* cooldowns.
        gate.finish("alice", nowMillis = 100L) // cooldown until 1_100L
        gate.finish("bob", nowMillis = 100L) // cooldown until 1_100L
        assertEquals(2, gate.retainedCooldownCount())

        // carol finishes after alice/bob cooldowns have elapsed; their stale
        // entries are swept even though no tryStart ran in between.
        gate.finish("carol", nowMillis = 2_000L)
        assertEquals(1, gate.retainedCooldownCount())
    }
}
