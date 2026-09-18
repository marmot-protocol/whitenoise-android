package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pacer lets a short burst through and then spaces writes one refill interval apart without dropping any. */
class NotificationPostPacerTest {
    /** A burst up to the capacity is immediate; the writes after it wait one interval each. */
    @Test
    fun burstPassesThenWritesAreSpaced() =
        runTest {
            val now = 1_000L
            val sleeps = mutableListOf<Long>()
            val pacer =
                NotificationPostPacer(
                    refillIntervalMillis = 200L,
                    burstCapacity = 2,
                    nowMillis = { now },
                    sleep = { sleeps += it },
                )

            assertEquals(0L, pacer.awaitSlot())
            assertEquals(0L, pacer.awaitSlot())
            assertEquals(200L, pacer.awaitSlot())
            assertEquals(400L, pacer.awaitSlot())
            assertEquals(listOf(200L, 400L), sleeps)
        }

    /** Tokens come back as time passes, up to the burst capacity and no further. */
    @Test
    fun tokensRefillWithTimeUpToTheCapacity() =
        runTest {
            var now = 0L
            val pacer =
                NotificationPostPacer(
                    refillIntervalMillis = 200L,
                    burstCapacity = 2,
                    nowMillis = { now },
                    sleep = {},
                )

            assertEquals(0L, pacer.awaitSlot())
            assertEquals(0L, pacer.awaitSlot())
            now += 200L
            assertEquals(0L, pacer.awaitSlot())
            assertEquals(200L, pacer.awaitSlot())
            now += 10_000L
            assertEquals(0L, pacer.awaitSlot())
            assertEquals(0L, pacer.awaitSlot())
            assertEquals(200L, pacer.awaitSlot())
        }
}
