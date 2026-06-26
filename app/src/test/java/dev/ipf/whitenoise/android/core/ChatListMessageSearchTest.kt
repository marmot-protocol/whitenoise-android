package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
    fun bodyMatchesUsesLocaleRootCaseFolding() =
        withDefaultLocale(Locale.forLanguageTag("tr")) {
            assertTrue(ChatListMessageSearch.bodyMatches("INDIGO", "i"))
        }

    @Test
    fun bodyMatchesNormalizesWhitespaceLikeSnippet() {
        assertTrue(ChatListMessageSearch.bodyMatches("before foo\n   bar after", "foo bar"))
        assertTrue(ChatListMessageSearch.bodyMatches("before foo bar after", "foo\n\n   bar"))
    }

    @Test
    fun blankNeedleNeverMatches() {
        assertFalse(ChatListMessageSearch.bodyMatches("anything", ""))
        assertFalse(ChatListMessageSearch.bodyMatches("anything", " \n\t "))
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
    fun snippetNeedleWhitespaceNormalizesLikeBody() {
        val s = ChatListMessageSearch.buildSnippet("before foo\n   bar after", "foo bar")!!
        assertEquals("before foo bar after", s.text)
        assertEquals("foo bar", s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun snippetNeedleInternalWhitespaceCollapsesBeforeSearch() {
        val s = ChatListMessageSearch.buildSnippet("before foo bar after", "foo\n\n   bar")!!
        assertEquals("before foo bar after", s.text)
        assertEquals("foo bar", s.text.substring(s.highlightStart, s.highlightEnd))
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

    // ---- titleOrPreviewMatches (issue #290, blocking review #1) --------------

    @Test
    fun titleOrPreviewMatchesWhenTitleContainsNeedle() {
        // A row whose title already matches must be classified as title/preview
        // so the UI suppresses the body snippet + tap-to-message focus.
        assertTrue(ChatListMessageSearch.titleOrPreviewMatches("Project Marmot", "no needle here", "marmot"))
    }

    @Test
    fun titleOrPreviewMatchesWhenPreviewContainsNeedle() {
        assertTrue(ChatListMessageSearch.titleOrPreviewMatches("Some chat", "see you at the cafe", "cafe"))
    }

    @Test
    fun titleOrPreviewDoesNotMatchWhenOnlyBodyWouldMatch() {
        // Neither title nor preview contains the needle: this is a body-only
        // match, so the UI keeps the snippet + focus.
        assertFalse(ChatListMessageSearch.titleOrPreviewMatches("Some chat", "latest preview line", "marmot"))
    }

    @Test
    fun titleOrPreviewMatchIsCaseInsensitive() {
        assertTrue(ChatListMessageSearch.titleOrPreviewMatches("Project MARMOT", "preview", "marmot"))
    }

    @Test
    fun titleOrPreviewUsesLocaleRootCaseFolding() =
        withDefaultLocale(Locale.forLanguageTag("tr")) {
            assertTrue(ChatListMessageSearch.titleOrPreviewMatches("INDIGO", "preview", "i"))
            assertTrue(ChatListMessageSearch.titleOrPreviewMatches("title", "INDIGO", "i"))
            assertTrue(ChatListMessageSearch.titleOrPreviewMatches("title", "preview", "i", description = "INDIGO"))
        }

    @Test
    fun titleOrPreviewBlankNeedleNeverMatches() {
        assertFalse(ChatListMessageSearch.titleOrPreviewMatches("anything", "anything", ""))
    }

    // ---- description match (issue #388) -------------------------------------

    @Test
    fun titleOrPreviewMatchesWhenDescriptionContainsNeedle() {
        // Regression for #388: the chat-list filter must also OR-match on the
        // group description, and a row whose only synchronous match is in the
        // description should be classified as title/preview/description so the
        // UI suppresses the body snippet line (no older message to scroll to).
        assertTrue(
            ChatListMessageSearch.titleOrPreviewMatches(
                displayTitle = "Some chat",
                previewText = "latest preview line",
                ciNeedle = "weekend",
                description = "weekend hike planning",
            ),
        )
    }

    @Test
    fun descriptionMatchIsCaseInsensitive() {
        assertTrue(
            ChatListMessageSearch.titleOrPreviewMatches(
                displayTitle = "Some chat",
                previewText = "preview",
                ciNeedle = "research",
                description = "RESEARCH WORKGROUP",
            ),
        )
    }

    @Test
    fun emptyDescriptionDoesNotMatch() {
        // The default empty description must not match any non-empty needle, so
        // existing call sites that don't pass the parameter keep their old
        // behaviour (no false positives just because description defaulted to "").
        assertFalse(
            ChatListMessageSearch.titleOrPreviewMatches(
                displayTitle = "Some chat",
                previewText = "no needle here",
                ciNeedle = "marmot",
            ),
        )
    }

    // ---- firstEligibleBodyMatch (issue #290, blocking review #2) -------------

    private data class Rec(
        override val kind: ULong,
        override val deleted: Boolean,
        override val plaintext: String,
        override val messageIdHex: String,
        override val timelineAt: ULong = 0uL,
    ) : ChatListMessageSearch.SearchableRecord

    @Test
    fun excludedNewerRowsDoNotHideAnOlderEligibleBodyMatch() {
        // Regression for blocking review #2: the engine `search` field returns
        // needle-matching rows but can't filter by kind/deleted. Place five
        // excluded matching rows (reaction, delete tombstone, edit, stream-start,
        // group-system) NEWER than a single eligible kind:9 body, all in one page.
        // Selection must scan past the excluded rows and still find the body.
        val records =
            listOf(
                Rec(kind = 7uL, deleted = false, plaintext = "marmot reaction", messageIdHex = "a1"),
                Rec(kind = 9uL, deleted = true, plaintext = "marmot deleted", messageIdHex = "a2"),
                Rec(kind = 1009uL, deleted = false, plaintext = "marmot edit", messageIdHex = "a3"),
                Rec(kind = 1200uL, deleted = false, plaintext = "marmot stream start", messageIdHex = "a4"),
                Rec(kind = 1210uL, deleted = false, plaintext = "marmot joined", messageIdHex = "a5"),
                Rec(kind = 9uL, deleted = false, plaintext = "the real marmot message", messageIdHex = "a6"),
            )
        val match = ChatListMessageSearch.firstEligibleBodyMatch(records, "marmot")
        assertEquals("a6", match?.messageIdHex)
    }

    @Test
    fun firstEligibleBodyMatchPicksNewestEligibleWhenSeveralQualify() {
        val records =
            listOf(
                Rec(kind = 7uL, deleted = false, plaintext = "marmot reaction", messageIdHex = "n1"),
                Rec(kind = 9uL, deleted = false, plaintext = "newest marmot body", messageIdHex = "n2"),
                Rec(kind = 1uL, deleted = false, plaintext = "older marmot body", messageIdHex = "n3"),
            )
        // Engine order is newest-first, so the first eligible row wins.
        assertEquals("n2", ChatListMessageSearch.firstEligibleBodyMatch(records, "marmot")?.messageIdHex)
    }

    @Test
    fun firstEligibleBodyMatchReturnsNullWhenNoEligibleRows() {
        val records =
            listOf(
                Rec(kind = 7uL, deleted = false, plaintext = "marmot reaction", messageIdHex = "x1"),
                Rec(kind = 9uL, deleted = true, plaintext = "marmot deleted", messageIdHex = "x2"),
            )
        assertNull(ChatListMessageSearch.firstEligibleBodyMatch(records, "marmot"))
    }

    @Test
    fun firstEligibleBodyMatchRequiresTheNeedle() {
        // An eligible kind whose body does NOT contain the needle is skipped.
        val records = listOf(Rec(kind = 9uL, deleted = false, plaintext = "no needle present", messageIdHex = "y1"))
        assertNull(ChatListMessageSearch.firstEligibleBodyMatch(records, "marmot"))
    }

    private fun withDefaultLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val old = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(old)
        }
    }
}
