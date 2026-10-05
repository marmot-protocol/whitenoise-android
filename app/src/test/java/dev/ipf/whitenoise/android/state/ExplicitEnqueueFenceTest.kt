package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitEnqueueFenceTest {
    private var now = 0L
    private val fence = ExplicitEnqueueFence(nowMillis = { now }, staleAfterMillis = STALE_MS)

    /** A request that arrives while another holds the fence for the same attachment is dropped. */
    @Test
    fun secondRequestForTheSameAttachmentIsFenced() {
        assertTrue(fence.tryAcquire("a"))
        assertFalse(fence.tryAcquire("a"))
    }

    /** Other attachments, and the same attachment after release, can acquire the fence. */
    @Test
    fun otherAttachmentsAndReleasedAttachmentsAcquire() {
        assertTrue(fence.tryAcquire("a"))
        assertTrue(fence.tryAcquire("b"))
        fence.release("a")
        assertTrue(fence.tryAcquire("a"))
    }

    /** A holder whose lookup never returned cannot block explicit requests forever. */
    @Test
    fun staleHolderIsIgnored() {
        assertTrue(fence.tryAcquire("a"))
        now = STALE_MS - 1
        assertFalse(fence.tryAcquire("a"))
        now = STALE_MS
        assertTrue(fence.tryAcquire("a"))
    }

    private companion object {
        const val STALE_MS = 1_000L
    }
}
