package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The image session's refusal ledger, which every domain lane reads and writes at once. */
class MediaRefusalLedgerTest {
    /** Many threads refusing and checking keys at once never throw, and the bound holds afterwards. */
    @Test
    fun concurrentRefusalsAndChecksKeepTheBound() {
        val ledger = MediaRefusalLedger(limit = LIMIT)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val threads =
            (0 until THREADS).map { thread ->
                Thread {
                    start.await()
                    try {
                        repeat(ROUNDS) { round ->
                            ledger.refuse("t$thread:${round % KEYS_PER_THREAD}", Long.MAX_VALUE)
                            ledger.refused("t${(thread + 1) % THREADS}:${round % KEYS_PER_THREAD}", 0L)
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }.apply { start() }
            }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
        assertTrue("A ledger thread is stuck", threads.none(Thread::isAlive))
        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(LIMIT, ledger.size())
    }

    /** The bound drops the oldest key, and refusing a key again makes it the newest. */
    @Test
    fun boundDropsTheOldestKeyAndARefreshedKeyIsNewest() {
        val ledger = MediaRefusalLedger(limit = 3)
        listOf("a", "b", "c").forEach { ledger.refuse(it, UNTIL) }
        ledger.refuse("a", UNTIL)
        ledger.refuse("d", UNTIL)
        assertFalse("b was the oldest key", ledger.refused("b", NOW))
        assertTrue(listOf("a", "c", "d").all { ledger.refused(it, NOW) })
        assertEquals(3, ledger.size())
    }

    /** A key is refused only until its expiry. */
    @Test
    fun keyIsRefusedOnlyUntilItsExpiry() {
        val ledger = MediaRefusalLedger()
        ledger.refuse("a", UNTIL)
        assertTrue(ledger.refused("a", UNTIL - 1))
        assertFalse(ledger.refused("a", UNTIL))
        assertFalse(ledger.refused("b", NOW))
    }

    private companion object {
        const val LIMIT = 64
        const val THREADS = 8
        const val ROUNDS = 20_000
        const val KEYS_PER_THREAD = 40
        const val JOIN_SECONDS = 20L
        const val NOW = 1_000L
        const val UNTIL = 61_000L
    }
}
