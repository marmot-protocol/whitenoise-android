package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StalenessGuardTest {
    /** Keeps a capture current until an explicit invalidation boundary advances. */
    @Test
    fun unchangedGuardKeepsCaptureCurrent() {
        val guard = StalenessGuard()

        assertTrue(guard.isCurrent(guard.capture()))
    }

    /** Makes every prior capture stale while returning the new current token. */
    @Test
    fun advanceInvalidatesEveryEarlierCapture() {
        val guard = StalenessGuard()
        val original = guard.capture()
        val first = guard.advance()
        val second = guard.advance()

        assertFalse(guard.isCurrent(original))
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    /** Accepts current publication and rejects a completion superseded while suspended. */
    @Test
    fun runIfCurrentPublishesOnlyTheNewestCompletion() {
        val guard = StalenessGuard()
        val stale = guard.capture()
        val current = guard.advance()
        val publications = mutableListOf<String>()

        assertFalse(guard.runIfCurrent(stale) { publications += "stale" })
        assertTrue(guard.runIfCurrent(current) { publications += "current" })
        assertEquals(listOf("current"), publications)
    }

    /** Compare-and-advance accepts one owner and rejects its superseded token. */
    @Test
    fun advanceIfCurrentIsAtomic() {
        val guard = StalenessGuard()
        val captured = guard.capture()

        assertEquals(1L, guard.advanceIfCurrent(captured))
        assertNull(guard.advanceIfCurrent(captured))
    }

    /** Serializes invalidation behind an already accepted publication block. */
    @Test
    fun advanceCannotInterleaveWithAcceptedPublication() {
        val guard = StalenessGuard()
        val captured = guard.capture()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val advanceFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication =
                executor.submit {
                    guard.runIfCurrent(captured) {
                        publicationEntered.countDown()
                        assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
            executor.execute {
                guard.advance()
                advanceFinished.countDown()
            }

            assertFalse(advanceFinished.await(100, TimeUnit.MILLISECONDS))
            releasePublication.countDown()
            assertTrue(advanceFinished.await(5, TimeUnit.SECONDS))
            publication.get(5, TimeUnit.SECONDS)
            assertFalse(guard.isCurrent(captured))
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
        }
    }

    /** Does not expose a new current token until its invalidation block is complete. */
    @Test
    fun advancePublishesItsTokenAfterInvalidation() {
        val guard = StalenessGuard()
        val original = guard.capture()
        val invalidationEntered = CountDownLatch(1)
        val releaseInvalidation = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val advance =
                executor.submit<Long> {
                    guard.advance {
                        invalidationEntered.countDown()
                        assertTrue(releaseInvalidation.await(5, TimeUnit.SECONDS))
                    }
                }

            assertTrue(invalidationEntered.await(5, TimeUnit.SECONDS))
            assertEquals(original, guard.capture())
            releaseInvalidation.countDown()
            assertEquals(original + 1L, advance.get(5, TimeUnit.SECONDS))
        } finally {
            releaseInvalidation.countDown()
            executor.shutdownNow()
        }
    }

    /** Allocates a unique monotonically increasing token under concurrent invalidation. */
    @Test
    fun concurrentAdvanceDoesNotLoseInvalidations() {
        val guard = StalenessGuard()
        val workers = 8
        val advancesPerWorker = 250
        val tokens = Collections.synchronizedSet(mutableSetOf<Long>())
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                List(workers) {
                    executor.submit {
                        repeat(advancesPerWorker) {
                            tokens += guard.advance()
                        }
                    }
                }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(workers * advancesPerWorker, tokens.size)
        assertEquals((workers * advancesPerWorker).toLong(), guard.capture())
    }
}
