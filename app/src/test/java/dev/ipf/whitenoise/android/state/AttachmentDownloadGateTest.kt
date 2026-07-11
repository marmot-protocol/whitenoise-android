package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

// One generous hang guard per test instead of tight per-step windows. The old
// 10s window inside each permit-holder could expire during a CI runner stall,
// releasing that permit early and admitting the queued third download before
// the bound assertions ran — an AssertionError that never reproduced locally.
private const val TEST_HANG_GUARD_MS = 30_000L

class AttachmentDownloadGateTest {
    @Test
    fun smallAlbumDownloadsCanStartTogether() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val gate = AttachmentDownloadGate(parallelism = 3)
                val active = AtomicInteger(0)
                val maxActive = AtomicInteger(0)
                val allStarted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                val jobs =
                    List(3) {
                        async(start = CoroutineStart.UNDISPATCHED) {
                            gate.withPermit {
                                val now = active.incrementAndGet()
                                maxActive.updateAndGet { previous -> max(previous, now) }
                                if (now == 3) allStarted.complete(Unit)
                                try {
                                    release.await()
                                } finally {
                                    active.decrementAndGet()
                                }
                            }
                        }
                    }

                allStarted.await()
                assertEquals(3, maxActive.get())
                release.complete(Unit)
                jobs.awaitAll()
            }
        }
    }

    @Test
    fun configuredLimitStillBoundsExcessDownloads() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val gate = AttachmentDownloadGate(parallelism = 2)
                val active = AtomicInteger(0)
                val started = AtomicInteger(0)
                val maxActive = AtomicInteger(0)
                val firstTwoStarted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                val jobs =
                    List(3) {
                        async(start = CoroutineStart.UNDISPATCHED) {
                            gate.withPermit {
                                val now = active.incrementAndGet()
                                val totalStarted = started.incrementAndGet()
                                maxActive.updateAndGet { previous -> max(previous, now) }
                                if (totalStarted == 2) firstTwoStarted.complete(Unit)
                                try {
                                    release.await()
                                } finally {
                                    active.decrementAndGet()
                                }
                            }
                        }
                    }

                firstTwoStarted.await()
                assertEquals(2, started.get())
                assertEquals(2, maxActive.get())
                release.complete(Unit)
                jobs.awaitAll()
                assertEquals(3, started.get())
            }
        }
    }

    @Test
    fun retriesTransientFailuresAfterReleasingPermit() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                retriesTransientFailuresBody()
            }
        }
    }

    private suspend fun retriesTransientFailuresBody() {
        val gate = AttachmentDownloadGate(parallelism = 1)
        val events = mutableListOf<String>()
        var attempts = 0
        val result =
            gate.withRetryingPermit(
                maxAttempts = 3,
                initialBackoffMillis = 25,
                maxBackoffMillis = 100,
                sleep = { delayMillis ->
                    events += "sleep:$delayMillis"
                    // Acquiring here proves the retrying caller released its
                    // permit before backing off; a regression deadlocks and
                    // trips the outer hang guard.
                    gate.withPermit { events += "sibling" }
                },
            ) {
                attempts += 1
                events += "attempt:$attempts"
                if (attempts < 3) error("transient")
                "ok"
            }

        assertEquals("ok", result)
        assertEquals(
            listOf(
                "attempt:1",
                "sleep:25",
                "sibling",
                "attempt:2",
                "sleep:50",
                "sibling",
                "attempt:3",
            ),
            events,
        )
    }

    @Test
    fun cancellationIsNotRetried() {
        runBlocking {
            val gate = AttachmentDownloadGate(parallelism = 1)
            var attempts = 0
            try {
                gate.withRetryingPermit(
                    maxAttempts = 3,
                    sleep = { fail("cancellation must not back off") },
                ) {
                    attempts += 1
                    throw CancellationException("gone")
                }
                fail("expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(1, attempts)
            }
        }
    }

    @Test
    fun initialRetryBackoffMustBePositive() {
        runBlocking {
            val gate = AttachmentDownloadGate(parallelism = 1)
            try {
                gate.withRetryingPermit(initialBackoffMillis = 0) {
                    fail("zero backoff should be rejected before the first attempt")
                }
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
