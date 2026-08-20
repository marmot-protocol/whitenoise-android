package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedInitialReadTest {
    @Test
    fun completedResourceTransfersToTheCallerWithinTheBudget() =
        runTest {
            var closed = false
            val resource = Any()

            assertSame(
                resource,
                awaitBoundedInitialResourceRead(
                    budgetMillis = 1_000L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                    read = { resource },
                    closeLate = { closed = true },
                    onTimeout = { error("unexpected timeout") },
                ),
            )
            assertEquals(false, closed)
        }

    @Test
    fun lateResourceIsClosedAfterTheCallerTimesOut() =
        runTest {
            val releaseRead = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            var closeCount = 0
            var timedOut = false
            try {
                awaitBoundedInitialResourceRead(
                    budgetMillis = 10L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                    read = {
                        releaseRead.await()
                        "subscription"
                    },
                    closeLate = {
                        closeCount += 1
                        closed.complete(Unit)
                    },
                    onTimeout = {
                        timedOut = true
                        throw ConversationInitialLoadTimeoutException()
                    },
                )
                fail("expected the timeout branch to throw")
            } catch (_: ConversationInitialLoadTimeoutException) {
            }

            releaseRead.complete(Unit)
            closed.await()
            assertTrue(timedOut)
            assertEquals(1, closeCount)
        }

    @Test
    fun failedReadRethrowsTheOriginalError() =
        runTest {
            val boom = IllegalArgumentException("local read failed")
            try {
                awaitBoundedInitialResourceRead(
                    budgetMillis = 1_000L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                    read = { throw boom },
                    closeLate = {},
                    onTimeout = { error("unexpected timeout") },
                )
                fail("expected the read failure to rethrow")
            } catch (thrown: IllegalArgumentException) {
                assertSame(boom, thrown)
            }
        }

    @Test
    fun successfulSubscriptionCannotLeaveAHangingInitialSnapshotUnbounded() =
        runTest {
            val subscription =
                awaitBoundedInitialResourceRead(
                    budgetMillis = 1_000L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                    read = { "subscription" },
                    closeLate = {},
                    onTimeout = { error("unexpected subscription timeout") },
                )
            val releaseSnapshot = CompletableDeferred<Unit>()
            val snapshotFinished = CompletableDeferred<Unit>()
            var timedOut = false
            val snapshotRead =
                SingleFlightBoundedInitialSnapshotRead<String>(
                    budgetMillis = 10L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                )

            assertEquals("subscription", subscription)

            try {
                snapshotRead.await {
                    releaseSnapshot.await()
                    snapshotFinished.complete(Unit)
                    "snapshot"
                }
                fail("expected the snapshot timeout")
            } catch (_: ConversationInitialLoadTimeoutException) {
                timedOut = true
            }

            releaseSnapshot.complete(Unit)
            snapshotFinished.await()
            snapshotRead.cancel()
            assertTrue(timedOut)
        }

    @Test
    fun repeatedTimeoutsAndRetriesKeepSnapshotReadSingleFlight() =
        runTest {
            val releaseSnapshot = CompletableDeferred<Unit>()
            var activeReads = 0
            var maxActiveReads = 0
            var startedReads = 0
            val snapshotRead =
                SingleFlightBoundedInitialSnapshotRead<String>(
                    budgetMillis = 10L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                )

            try {
                repeat(3) { attempt ->
                    try {
                        snapshotRead.await {
                            startedReads += 1
                            activeReads += 1
                            maxActiveReads = maxOf(maxActiveReads, activeReads)
                            try {
                                releaseSnapshot.await()
                                "snapshot"
                            } finally {
                                activeReads -= 1
                            }
                        }
                        fail("expected the bounded snapshot read to remain unavailable")
                    } catch (throwable: ConversationInitialLoadException) {
                        if (attempt == 0) {
                            assertTrue(throwable is ConversationInitialLoadTimeoutException)
                        } else {
                            assertTrue(throwable is ConversationInitialLoadStillInFlightException)
                        }
                    }
                }

                assertEquals(1, startedReads)
                assertEquals(1, maxActiveReads)
                assertEquals(1, activeReads)

                releaseSnapshot.complete(Unit)
                testScheduler.runCurrent()
                assertEquals(
                    "snapshot",
                    snapshotRead.await { error("a retry must consume the retained producer result") },
                )
                assertEquals(0, activeReads)
                assertEquals(1, startedReads)
            } finally {
                releaseSnapshot.complete(Unit)
                snapshotRead.cancel()
            }
        }

    @Test
    fun onlyNativeOpenTimeoutsRequireExplicitRetry() {
        assertTrue(
            isTerminalOpenFailure(
                ConversationInitialLoadTimeoutException(),
            ),
        )
        assertTrue(
            isTerminalOpenFailure(
                ConversationInitialLoadStillInFlightException(),
            ),
        )
        assertEquals(
            false,
            isTerminalOpenFailure(IllegalStateException("stream ended")),
        )
    }
}
