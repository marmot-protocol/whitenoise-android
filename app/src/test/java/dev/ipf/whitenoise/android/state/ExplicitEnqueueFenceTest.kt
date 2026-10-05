package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitEnqueueFenceTest {
    private var now = 0L
    private val fence = ExplicitEnqueueFence(nowMillis = { now }, staleAfterMillis = STALE_MS)

    /** A request that arrives while another holds the fence for the same attachment is dropped. */
    @Test
    fun secondRequestForTheSameAttachmentIsFenced() {
        assertNotNull(fence.tryAcquire("a"))
        assertNull(fence.tryAcquire("a"))
    }

    /** Other attachments, and the same attachment after release, can acquire the fence. */
    @Test
    fun otherAttachmentsAndReleasedAttachmentsAcquire() {
        val first = fence.tryAcquire("a")!!
        assertNotNull(fence.tryAcquire("b"))
        fence.release(first)
        assertNotNull(fence.tryAcquire("a"))
    }

    /** A holder whose lookup never returned cannot block explicit requests forever. */
    @Test
    fun staleHolderIsReplacedByASuccessor() {
        val first = fence.tryAcquire("a")!!
        now = STALE_MS - 1
        assertNull(fence.tryAcquire("a"))
        now = STALE_MS
        val successor = fence.tryAcquire("a")
        assertNotNull(successor)
        assertFalse(fence.isCurrent(first))
        assertTrue(fence.isCurrent(successor!!))
    }

    /** A superseded holder can neither release its successor's lease nor run a settlement. */
    @Test
    fun supersededHolderCannotReleaseOrSettle() {
        val first = fence.tryAcquire("a")!!
        now = STALE_MS
        val successor = fence.tryAcquire("a")!!

        fence.release(first)
        var settled = false
        val ran = fence.runIfCurrent(first) { settled = true }

        assertFalse(ran)
        assertFalse(settled)
        assertTrue(fence.isCurrent(successor))
        assertNull("the successor's lease was cleared by the old holder", fence.tryAcquire("a"))
    }

    /** The current holder settles exactly once under the lock and may then release. */
    @Test
    fun currentHolderSettlesAndReleases() {
        val lease = fence.tryAcquire("a")!!
        var settled = 0

        assertTrue(fence.runIfCurrent(lease) { settled += 1 })
        fence.release(lease)

        assertEquals(1, settled)
        assertNotNull(fence.tryAcquire("a"))
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
