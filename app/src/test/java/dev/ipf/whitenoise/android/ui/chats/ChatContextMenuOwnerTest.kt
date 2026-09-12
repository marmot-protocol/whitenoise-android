package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening identity and pointer ownership are independent from recycled row IDs and account labels. */
class ChatContextMenuOwnerTest {
    @Test fun olderHeldPointerCannotReleaseOrDismissNewOpening() {
        val owner = ChatContextMenuOwner()
        val first = owner.open(held = true)
        owner.dismiss(first)
        val second = owner.open(held = true)
        assertNotSame(first, second)
        owner.release(first)
        owner.dismiss(first)
        assertTrue(owner.pointerHeld)
        assertTrue(owner.isCurrent(second))
        owner.release(second)
        assertFalse(owner.pointerHeld)
        owner.dismiss(second)
        assertFalse(owner.isCurrent(second))
        assertTrue(owner.isLatest(second))
        assertFalse(owner.isLatest(first))
    }

    @Test fun disposedOwnerCannotReviveAfterAnotherOwnerIsCreated() {
        val previous = ChatContextMenuOwner()
        val stale = previous.open(held = true)
        previous.dispose()
        val replacement = ChatContextMenuOwner()
        val fresh = replacement.open(held = true)
        assertFalse(previous.isCurrent(stale))
        assertFalse(previous.isLatest(stale))
        assertFalse(replacement.isCurrent(stale))
        assertNotSame(stale, fresh)
        assertNull(previous.open(held = false))
        previous.release(stale)
        previous.dismiss(stale)
        assertTrue(replacement.isCurrent(fresh))
        assertTrue(replacement.pointerHeld)
    }
}
