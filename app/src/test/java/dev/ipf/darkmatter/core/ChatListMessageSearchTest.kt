package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListMessageSearchTest {
    // ---- isSearchableBody ---------------------------------------------------

    @Test
    fun searchableBodyKindsAreChatNoteAndStreamFinal() {
        assertTrue(ChatListMessageSearch.isSearchableBody(kind = 1uL, deleted = false, plaintext = "hi"))
        assertTrue(ChatListMessageSearch.isSearchableBody(kind = 9uL, deleted = false, plaintext = "hi"))
        assertTrue(ChatListMessageSearch.isSearchableBody(kind = 1209uL, deleted = false, plaintext = "hi"))
    }

    @Test
    fun nonBodyKindsAreNotSearchable() {
        // reaction (7), delete (5), edit (1009), stream-start (1200), group-system (1210)
        listOf(5uL, 7uL, 1009uL, 1200uL, 1210uL).forEach { kind ->
            assertFalse(
                "kind=$kind should not be searchable",
                ChatListMessageSearch.isSearchableBody(kind = kind, deleted = false, plaintext = "hi"),
            )
        }
    }

    @Test
    fun deletedOrBlankBodiesAreNotSearchable() {
        assertFalse(ChatListMessageSearch.isSearchableBody(kind = 9uL, deleted = true, plaintext = "hi"))
        assertFalse(ChatListMessageSearch.isSearchableBody(kind = 9uL, deleted = false, plaintext = ""))
        assertFalse(ChatListMessageSearch.isSearchableBody(kind = 9uL, deleted = false, plaintext = "   "))
    }

    // ---- bodyMatches --------------------------------------------------------

    @Test
    fun bodyMatchesIsCaseInsensitive() {
        assertTrue(ChatListMessageSearch.bodyMatches("Let's grab Tacos later", "tacos"))
        assertTrue(ChatListMessageSearch.bodyMatches("lowercase needle", "NEEDLE".lowercase()))
        assertFalse(ChatListMessageSearch.bodyMatches("nothing here", "absent"))
    }

    @Test
    fun blankNeedleNeverMatches() {
        assertFalse(ChatListMessageSearch.bodyMatches("anything", ""))
    }

    // ---- buildSnippet -------------------------------------------------------

    @Test
    fun shortBodyReturnedWholeWithCorrectHighlight() {
        val s = ChatListMessageSearch.buildSnippet("hello world", "world")!!
        assertEquals("hello world", s.text)
        assertEquals("world", s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun missingNeedleReturnsNull() {
        assertNull(ChatListMessageSearch.buildSnippet("hello world", "absent"))
        assertNull(ChatListMessageSearch.buildSnippet("hello world", ""))
    }

    @Test
    fun whitespaceRunsCollapseToSingleSpaces() {
        val s = ChatListMessageSearch.buildSnippet("hello\n\n   spaced\tout world", "spaced")!!
        assertEquals("hello spaced out world", s.text)
        assertEquals("spaced", s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun longBodyClipsAroundMatchWithEllipses() {
        val needle = "MARMOT"
        val body = "a".repeat(200) + needle + "b".repeat(200)
        val s = ChatListMessageSearch.buildSnippet(body, needle, maxLength = 40)!!
        // The needle is interior, so both ends are clipped.
        assertTrue("expected leading ellipsis, got: ${s.text}", s.text.startsWith("\u2026"))
        assertTrue("expected trailing ellipsis, got: ${s.text}", s.text.endsWith("\u2026"))
        // Highlight range still points at the needle within the snippet.
        assertEquals(needle, s.text.substring(s.highlightStart, s.highlightEnd))
        // Snippet stays within budget plus the two ellipsis characters.
        assertTrue(s.text.length <= 42)
    }

    @Test
    fun matchNearStartHasNoLeadingEllipsis() {
        val needle = "start"
        val body = needle + " " + "x".repeat(200)
        val s = ChatListMessageSearch.buildSnippet(body, needle, maxLength = 40)!!
        assertFalse(s.text.startsWith("\u2026"))
        assertTrue(s.text.endsWith("\u2026"))
        assertEquals(needle, s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun matchNearEndHasNoTrailingEllipsis() {
        val needle = "finish"
        val body = "x".repeat(200) + " " + needle
        val s = ChatListMessageSearch.buildSnippet(body, needle, maxLength = 40)!!
        assertTrue(s.text.startsWith("\u2026"))
        assertFalse(s.text.endsWith("\u2026"))
        assertEquals(needle, s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun highlightRangeIsAlwaysValidSubstring() {
        // Adversarial: needle longer than the window. The highlight range must
        // still be a valid (clamped) substring of the snippet text.
        val needle = "z".repeat(60)
        val body = "head " + needle + " tail"
        val s = ChatListMessageSearch.buildSnippet(body, needle, maxLength = 20)!!
        assertTrue(s.highlightStart in 0..s.text.length)
        assertTrue(s.highlightEnd in s.highlightStart..s.text.length)
        // substring call must not throw
        s.text.substring(s.highlightStart, s.highlightEnd)
    }

    @Test
    fun firstOccurrenceIsHighlightedWhenNeedleRepeats() {
        val s = ChatListMessageSearch.buildSnippet("alpha beta alpha", "alpha")!!
        assertEquals(0, s.highlightStart)
        assertEquals(5, s.highlightEnd)
    }
}
