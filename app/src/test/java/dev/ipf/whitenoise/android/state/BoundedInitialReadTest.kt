package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedInitialReadTest {
    @Test
    fun completedReadReturnsItsValueWithinTheBudget() =
        runTest {
            val read = CompletableDeferred(Result.success("page"))
            assertEquals(
                "page",
                awaitBoundedInitialRead(read, budgetMillis = 10L) { error("unexpected timeout") },
            )
        }

    @Test
    fun hungReadIsCancelledAndExitsThroughTheTimeoutBranch() =
        runTest {
            val read = CompletableDeferred<Result<String>>()
            var timedOut = false
            try {
                awaitBoundedInitialRead(read, budgetMillis = 10L) {
                    timedOut = true
                    throw ConversationInitialSnapshotTimeoutException()
                }
                fail("expected the timeout branch to throw")
            } catch (_: ConversationInitialSnapshotTimeoutException) {
            }
            assertTrue(timedOut)
            assertTrue(read.isCancelled)
        }

    @Test
    fun failedReadRethrowsTheOriginalError() =
        runTest {
            val boom = IllegalArgumentException("local read failed")
            val read = CompletableDeferred(Result.failure<String>(boom))
            try {
                awaitBoundedInitialRead(read, budgetMillis = 10L) { error("unexpected timeout") }
                fail("expected the read failure to rethrow")
            } catch (thrown: IllegalArgumentException) {
                assertSame(boom, thrown)
            }
        }
}
