package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStateRetentionTest {
    @Test
    fun retainEvictsOldestWhenNoConversationIsProtected() {
        val retention = ConversationStateRetention(maxEntries = 3)

        assertEquals(emptyList<String>(), retention.retain("a"))
        retention.retain("b")
        retention.retain("c")

        assertEquals(listOf("a"), retention.retain("d"))
        assertEquals(listOf("b", "c", "d"), retention.keysSnapshot())
    }

    @Test
    fun protectedConversationSurvivesOverflowEvenWhenItWouldBeOldest() {
        val retention = ConversationStateRetention(maxEntries = 3)

        retention.retain("active")
        retention.retain("b", protectedKey = "active")
        retention.retain("c", protectedKey = "active")

        assertEquals(listOf("b"), retention.retain("d", protectedKey = "active"))
        assertEquals(listOf("c"), retention.retain("e", protectedKey = "active"))

        val keys = retention.keysSnapshot()
        assertTrue("active conversation state must not be evicted", "active" in keys)
        assertFalse("least-recent non-active state should be evicted first", "b" in keys)
        assertFalse("next least-recent non-active state should be evicted first", "c" in keys)
        assertEquals(3, keys.size)
    }

    @Test
    fun promoteKeepsAnAlreadyRetainedConversationFromBeingNextEvicted() {
        val retention = ConversationStateRetention(maxEntries = 3)

        retention.retain("a")
        retention.retain("b")
        retention.retain("c")
        retention.promote("a", protectedKey = "a")

        assertEquals(listOf("b"), retention.retain("d"))
        assertEquals(listOf("c", "a", "d"), retention.keysSnapshot())
    }
}
