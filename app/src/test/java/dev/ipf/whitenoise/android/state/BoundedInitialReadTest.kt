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

            assertEquals("subscription", subscription)

            try {
                awaitBoundedInitialSnapshotRead(
                    budgetMillis = 10L,
                    producerDispatcher = StandardTestDispatcher(testScheduler),
                    read = {
                        releaseSnapshot.await()
                        snapshotFinished.complete(Unit)
                        "snapshot"
                    },
                )
                fail("expected the snapshot timeout")
            } catch (_: ConversationInitialLoadTimeoutException) {
                timedOut = true
            }

            releaseSnapshot.complete(Unit)
            snapshotFinished.await()
            assertTrue(timedOut)
        }

    @Test
    fun onlyNativeOpenTimeoutsRequireExplicitRetry() {
        assertTrue(
            isTerminalOpenFailure(
                ConversationInitialLoadTimeoutException(),
            ),
        )
        assertEquals(
            false,
            isTerminalOpenFailure(IllegalStateException("stream ended")),
        )
    }
}
