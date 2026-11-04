package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exact-key waiter registry behind cached-avatar adoption (#3557). */
class AvatarCachePublicationsTest {
    /** Waiters on one key coalesce under it: one publication wakes all of them, and cleanup is exact per waiter. */
    @Test
    fun waitersOnOneKeyCoalesceAndReleaseExactly() {
        val publications = AvatarCachePublications()
        val first = publications.register(setOf("a"))
        val second = publications.register(setOf("a"))
        val other = publications.register(setOf("b"))
        assertEquals(2, publications.waitingKeyCount)

        publications.published("a")
        assertTrue(first.published.isCompleted)
        assertTrue(second.published.isCompleted)
        assertFalse("another key's waiter is not woken", other.published.isCompleted)

        publications.release(first)
        assertEquals("the key stays while a waiter remains", 2, publications.waitingKeyCount)
        publications.release(second)
        publications.release(other)
        assertEquals(0, publications.waitingKeyCount)
    }
}
