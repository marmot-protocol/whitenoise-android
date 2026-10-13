package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BoundedMediaExecutorTest {
    @Test
    fun timeoutDoesNotReleaseNativeAdmissionAndLateResultIsDisposed() {
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val calls = AtomicInteger()
        val decoder = BoundedMediaExecutor(worker)
        try {
            val first =
                callers.submit<Int?> {
                    decoder.run(100L, { true }, { disposed.countDown() }) {
                        calls.incrementAndGet()
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        7
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNull(first.get(5, TimeUnit.SECONDS))
            assertNull(decoder.run(10L, { true }) { calls.incrementAndGet() })
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            assertEquals(8, decoder.run(5000L, { true }) { 8 })
        } finally {
            release.countDown()
            callers.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun accountRetirementFencesDeliveryAndCooperativelyCancelsWork() {
        val worker = Executors.newSingleThreadExecutor()
        val callers = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val completed = AtomicBoolean(false)
        val decoder = BoundedMediaExecutor(worker)
        try {
            val first =
                callers.submit<Int?> {
                    decoder.run(5000L, current::get) { check ->
                        started.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        check()
                        completed.set(true)
                        7
                    }
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            current.set(false)
            release.countDown()
            assertNull(first.get(5, TimeUnit.SECONDS))
            assertTrue(!completed.get())
            assertEquals(9, decoder.run(5000L, { true }) { 9 })
        } finally {
            release.countDown()
            callers.shutdownNow()
            worker.shutdownNow()
        }
    }

    @Test
    fun rejectedExecutionReleasesAdmissionAndDoesNotRunSourceWork() {
        val calls = AtomicInteger()
        val decoder = BoundedMediaExecutor { throw java.util.concurrent.RejectedExecutionException() }
        repeat(2) {
            try {
                decoder.run(10L, { true }) { calls.incrementAndGet() }
                throw AssertionError("Rejected execution must fail closed")
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                assertEquals(0, calls.get())
            }
        }
    }
}
