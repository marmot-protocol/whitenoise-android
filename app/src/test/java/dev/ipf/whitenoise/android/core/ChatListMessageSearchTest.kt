package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
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
    fun bodyMatchesBoundsVeryLargeBodies() {
        val body = "a".repeat(5_000) + " marmot"
        assertFalse(ChatListMessageSearch.bodyMatches(body, "marmot"))
    }

    /** Full-body conversation matching shares normalization without adopting the home preview limit. */
    @Test
    fun conversationMatchingCanKeepTheCompleteBodyWithSharedNormalization() {
        val body = "a".repeat(5_000) + " marmot\n   friends"
        assertTrue(ChatListMessageSearch.bodyMatches(body, "marmot friends", scanLimit = Int.MAX_VALUE))
        assertFalse(ChatListMessageSearch.bodyMatches(body, "marmot friends"))
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
    fun snippetSearchBoundsVeryLargeBodies() {
        val body = "a".repeat(5_000) + " marmot"
        assertNull(ChatListMessageSearch.buildSnippet(body, "marmot"))
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

    @Test
    fun shortSnippetHighlightsNeedleAfterLengthChangingCaseFold() {
        val needle = "hello"
        val s = ChatListMessageSearch.buildSnippet("X İ $needle", needle)!!
        assertEquals(needle, s.text.substring(s.highlightStart, s.highlightEnd))
    }

    @Test
    fun clippedSnippetHighlightsNeedleAfterLengthChangingCaseFold() {
        val needle = "marmot"
        val body = "İ" + "x".repeat(200) + needle + "y".repeat(200)
        val s = ChatListMessageSearch.buildSnippet(body, needle, maxLength = 40)!!
        assertEquals(needle, s.text.substring(s.highlightStart, s.highlightEnd))
    }

    // ---- first eligible match in engine order (issue #290, blocking review #2) ----

    private data class Rec(
        override val kind: ULong,
        override val deleted: Boolean,
        override val plaintext: String,
        override val messageIdHex: String,
        override val timelineAt: ULong = 0uL,
        override val sender: String = "",
        override val mediaTypes: List<String> = emptyList(),
        override val mediaLabels: List<String> = emptyList(),
    ) : ChatListMessageSearch.SearchableRecord

    // ---- prototype filters (senders / date / content) ------------------------

    /** Constraints match sender date and content across categories. */
    @Test
    fun constraintsMatchSenderDateAndContentAcrossCategories() {
        val constraints =
            MessageSearchConstraints(
                senderIds = setOf("abc"),
                dateBounds = GlobalSearchEpochBounds(1_000_000L, 2_000_000L),
                contentKinds = setOf(GlobalSearchContentKind.IMAGES_VIDEO, GlobalSearchContentKind.LINKS),
            )
        val photo = Rec(9uL, false, "", "m1", timelineAt = 1_500uL, sender = "ABC", mediaTypes = listOf("image/png"))
        assertTrue(constraints.matches(photo))
        assertFalse(constraints.matches(photo.copy(sender = "zzz")))
        assertFalse(constraints.matches(photo.copy(timelineAt = 2_000uL)))
        assertFalse(constraints.matches(photo.copy(mediaTypes = listOf("application/pdf"))))
        assertTrue(constraints.matches(photo.copy(mediaTypes = emptyList(), plaintext = "see https://x.y/z")))
    }

    /** A stamp past the displayable bound matches no date range, even where its millisecond product wraps into one. */
    @Test
    fun undisplayableStampsMatchNoDateRange() {
        val constraints = MessageSearchConstraints(dateBounds = GlobalSearchEpochBounds(0L, 2_000_000L))
        val photo = Rec(9uL, false, "", "m1", timelineAt = 1_500uL)
        assertTrue(constraints.matches(photo))
        // 18_446_744_073_709_552 seconds is 384 ms once the signed millisecond product wraps.
        val wrapping = listOf(18_446_744_073_709_552uL, IdentityFormatter.MAX_DISPLAYABLE_EPOCH_SECONDS.toULong() + 1uL)
        for (stamp in wrapping + ULong.MAX_VALUE) {
            assertFalse("$stamp", constraints.matches(photo.copy(timelineAt = stamp)))
        }
    }

    /** Content kinds follow the prototype classification. */
    @Test
    fun contentKindsFollowThePrototypeClassification() {
        assertEquals(
            setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS),
            messageSearchContentKinds("read www.example.org now", emptyList()),
        )
        assertEquals(
            setOf(
                GlobalSearchContentKind.ANY_ATTACHMENT,
                GlobalSearchContentKind.VOICE_AUDIO,
                GlobalSearchContentKind.FILES_DOCUMENTS,
            ),
            messageSearchContentKinds("", listOf("audio/ogg", "application/pdf")),
        )
    }

    /** Filter only search accepts attachment rows and builds a label snippet. */
    @Test
    fun filterOnlySearchAcceptsAttachmentRowsAndBuildsALabelSnippet() {
        val records =
            listOf(
                Rec(7uL, false, "reaction", "r1", mediaTypes = listOf("image/png")),
                Rec(9uL, true, "", "d1", mediaTypes = listOf("image/png")),
                Rec(9uL, false, "", "p1", mediaTypes = listOf("image/png"), mediaLabels = listOf("beach.png")),
            )
        val match = records.firstOrNull { ChatListMessageSearch.isEligibleMatch(it, "", MessageSearchConstraints()) }
        assertEquals("p1", match?.messageIdHex)
        val snippet = ChatListMessageSearch.buildFilteredSnippet("", listOf("beach.png"))
        assertEquals("beach.png", snippet?.text)
        assertEquals(0, snippet?.highlightEnd)
        assertNull(ChatListMessageSearch.buildFilteredSnippet("  ", emptyList()))
        assertEquals("hello world", ChatListMessageSearch.buildFilteredSnippet("hello  world", emptyList())?.text)
    }

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
        val match = records.firstOrNull { ChatListMessageSearch.isEligibleMatch(it, "marmot") }
        assertEquals("a6", match?.messageIdHex)
    }

    /** In engine order the newest eligible row is the first match. */
    @Test
    fun firstEligibleMatchPicksNewestEligibleWhenSeveralQualify() {
        val records =
            listOf(
                Rec(kind = 7uL, deleted = false, plaintext = "marmot reaction", messageIdHex = "n1"),
                Rec(kind = 9uL, deleted = false, plaintext = "newest marmot body", messageIdHex = "n2"),
                Rec(kind = 1uL, deleted = false, plaintext = "older marmot body", messageIdHex = "n3"),
            )
        // Engine order is newest-first, so the first eligible row wins.
        assertEquals("n2", records.firstOrNull { ChatListMessageSearch.isEligibleMatch(it, "marmot") }?.messageIdHex)
    }

    /** Reactions and deleted rows never count as a match. */
    @Test
    fun firstEligibleMatchReturnsNullWhenNoEligibleRows() {
        val records =
            listOf(
                Rec(kind = 7uL, deleted = false, plaintext = "marmot reaction", messageIdHex = "x1"),
                Rec(kind = 9uL, deleted = true, plaintext = "marmot deleted", messageIdHex = "x2"),
            )
        assertNull(records.firstOrNull { ChatListMessageSearch.isEligibleMatch(it, "marmot") })
    }

    /** An eligible kind without the needle is not a match. */
    @Test
    fun firstEligibleMatchRequiresTheNeedle() {
        // An eligible kind whose body does NOT contain the needle is skipped.
        val records = listOf(Rec(kind = 9uL, deleted = false, plaintext = "no needle present", messageIdHex = "y1"))
        assertNull(records.firstOrNull { ChatListMessageSearch.isEligibleMatch(it, "marmot") })
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
