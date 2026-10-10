package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foreground barriers follow committed local work without waiting for later messages or remote work. */
class ConversationTimelineHandoffTest {
    /** A foreground capture cannot be extended indefinitely by later arriving windows. */
    @Test
    fun foregroundWaitCoversOnlyReceiptsKnownAtItsBoundary() =
        runTest {
            val handoff = ConversationTimelineHandoff()
            val first = handoff.received()
            val captured = checkNotNull(handoff.pendingAtForeground())
            val later = handoff.received()
            val waiting = async { captured.await() }
            assertFalse(waiting.isCompleted)

            handoff.settled(listOf(first), applied = true)

            assertTrue(waiting.await())
            assertFalse(later.isCompleted)
            handoff.close()
            assertFalse(later.await())
        }

    /** The newest coalesced replacement commits every receipt it subsumes. */
    @Test
    fun coalescedBatchSettlesOnlyAfterItsActualCommit() =
        runTest {
            val handoff = ConversationTimelineHandoff()
            val receipts = List(32) { handoff.received() }
            assertTrue(receipts.none { it.isCompleted })

            handoff.settled(receipts, applied = true)

            assertTrue(receipts.all { it.await() })
            assertNull(handoff.pendingAtForeground())
        }

    /** A superseded preparation cannot manufacture a successful content-commit result. */
    @Test
    fun supersededPreparationAndRetiredAttemptDoNotClaimACommit() =
        runTest {
            val old = ConversationTimelineHandoff()
            val superseded = old.received()
            old.settled(listOf(superseded), applied = false)
            assertFalse(superseded.await())
            val pending = old.received()
            old.close()
            old.settled(listOf(pending), applied = true)
            assertFalse(pending.await())
            assertFalse(old.received().await())

            val replacement = ConversationTimelineHandoff()
            val current = replacement.received()
            assertFalse(current.isCompleted)
            replacement.settled(listOf(current), applied = true)
            assertTrue(current.await())
        }

    /** Cancelling an old foreground epoch does not cancel its independently owned live consumer. */
    @Test
    fun supersededForegroundWaitLeavesTheReceiptForItsCurrentOwner() =
        runTest {
            val handoff = ConversationTimelineHandoff()
            val receipt = handoff.received()
            val stale = launch { receipt.await() }
            stale.cancelAndJoin()
            assertFalse(receipt.isCancelled)
            handoff.settled(listOf(receipt), applied = true)
            assertTrue(receipt.await())
        }
}
