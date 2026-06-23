package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDataSearchTest {
    private val entries =
        listOf(
            EmojiEntry(emoji = "😀", name = "grinning face", group = 0, keywords = listOf("happy", "smile")),
            EmojiEntry(emoji = "🔥", name = "fire", group = 8, keywords = listOf("flame", "lit", "hot")),
            EmojiEntry(emoji = "🎉", name = "party popper", group = 5, keywords = listOf("party", "celebration", "tada")),
            EmojiEntry(emoji = "😟", name = "worried face", group = 0, keywords = listOf("concern")),
        )

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(EmojiData.search(entries, "").isEmpty())
        assertTrue(EmojiData.search(entries, "   ").isEmpty())
    }

    @Test
    fun matchesNameAndKeywordsCaseInsensitively() {
        assertEquals(listOf("🔥"), EmojiData.search(entries, "Fire").map { it.emoji })
        assertEquals(listOf("🎉"), EmojiData.search(entries, "tada").map { it.emoji })
        assertEquals(listOf("😀"), EmojiData.search(entries, "smile").map { it.emoji })
    }

    @Test
    fun exactAndPrefixMatchesRankAheadOfSubstring() {
        val results = EmojiData.search(entries, "fire").map { it.emoji }
        assertEquals("🔥", results.first())
    }

    @Test
    fun nonNegativeLimitGuard() {
        assertTrue(EmojiData.search(entries, "fire", limit = 0).isEmpty())
        assertTrue(EmojiData.search(entries, "fire", limit = -1).isEmpty())
    }
}
