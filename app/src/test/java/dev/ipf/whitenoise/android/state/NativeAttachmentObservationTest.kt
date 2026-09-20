package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAttachmentObservationTest {
    /** A demand exception still releases the subscription acquired before admission. */
    @Test
    fun demandFailureClosesObservation() =
        runTest {
            val feed = Feed(listOf(AttachmentTransferStateFfi.NOT_REQUESTED))
            val failure = runCatching { awaitNativeAttachment(feed) { error("admission failed") } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(1, feed.closes)
        }

    /** Leaving the screen closes observation only; it does not call native acquisition cancellation. */
    @Test
    fun cancelledWaitClosesObservation() =
        runTest {
            val feed = Feed(listOf(AttachmentTransferStateFfi.NOT_REQUESTED))
            val failure =
                runCatching {
                    awaitNativeAttachment(feed) { throw CancellationException("observer left") }
                }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, feed.closes)
        }

    /** An old cancelled initial snapshot cannot reject a newly accepted explicit acquisition. */
    @Test
    fun initialSnapshotIsNotTheNewDemandOutcome() =
        runTest {
            val feed = Feed(listOf(AttachmentTransferStateFfi.CANCELLED, AttachmentTransferStateFfi.READY))
            awaitNativeAttachment(feed) { null }
            assertEquals(1, feed.closes)
        }

    /** Automatic demand never starts a fresh cycle after any new native terminal outcome. */
    @Test
    fun terminalDemandDoesNotWaitOrRetry() =
        runTest {
            for (terminal in listOf(
                AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
                AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
                AttachmentTransferStateFfi.RETRY_EXHAUSTED,
            )) {
                val feed = Feed(listOf(AttachmentTransferStateFfi.NOT_REQUESTED))
                val failure = runCatching { awaitNativeAttachment(feed) { terminal } }.exceptionOrNull()
                assertTrue(failure is NativeAttachmentTerminalException)
                assertEquals(1, feed.closes)
            }
        }

    /** Bounded scripted snapshots fail immediately if the observer requests an unexpected extra read. */
    private class Feed(
        states: List<AttachmentTransferStateFfi>,
    ) : NativeTransferFeed {
        private val remaining = ArrayDeque(states)
        var closes = 0

        /** Emits complete one-target replacements, matching the native subscription contract. */
        override suspend fun next(): AttachmentTransferSnapshotFfi =
            AttachmentTransferSnapshotFfi(
                listOf(AttachmentTransferStatusFfi(null, remaining.removeFirst(), 0u, 0u, null, null)),
            )

        /** Records lexical disposal without pretending it cancels the native worker. */
        override fun close() {
            closes += 1
        }
    }
}
