package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionComposerTest {
    // A canonical 63-char npub for chip/insertion assertions.
    private val aliceNpub = "npub1" + "q".repeat(58)
    private val bobNpub = "npub1" + "p".repeat(58)

    private val alice =
        MentionComposer.Candidate(
            accountIdHex = "aa".repeat(32),
            npub = aliceNpub,
            displayName = "Alice",
            nip05 = "alice@example.com",
        )
    private val bob =
        MentionComposer.Candidate(
            accountIdHex = "bb".repeat(32),
            npub = bobNpub,
            displayName = "Bob Roberts",
            nip05 = "bobby@relay.io",
        )
    private val candidates = listOf(alice, bob)

    // --- activeMentionQuery -------------------------------------------------

    @Test
    fun bareAtAtCaretOpensEmptyQuery() {
        val q = MentionComposer.activeMentionQuery("hey @", caret = 5)
        assertEquals(MentionComposer.ActiveQuery(start = 4, query = ""), q)
    }

    @Test
    fun atStartOfTextOpensQuery() {
        val q = MentionComposer.activeMentionQuery("@al", caret = 3)
        assertEquals(MentionComposer.ActiveQuery(start = 0, query = "al"), q)
    }

    @Test
    fun queryStopsAtWhitespaceBeforeAt() {
        // Caret after "world": the nearest thing left is a space, not an @.
        assertNull(MentionComposer.activeMentionQuery("@al world", caret = 9))
    }

    @Test
    fun atInsideWordDoesNotOpenQuery() {
        // An email-like `foo@bar` must not trigger the picker.
        assertNull(MentionComposer.activeMentionQuery("foo@bar", caret = 7))
    }

    @Test
    fun caretBeforeAtIsNotAQuery() {
        assertNull(MentionComposer.activeMentionQuery("a @bob", caret = 1))
    }

    @Test
    fun completedChipDoesNotReopenQuery() {
        // Caret at the end of an inserted chip: no picker.
        val text = "hey @$aliceNpub"
        assertNull(MentionComposer.activeMentionQuery(text, caret = text.length))
    }

    @Test
    fun caretInsideInsertedChipDoesNotReopenQuery() {
        // Caret a few chars into the npub body of an inserted chip must NOT be
        // treated as a fresh `@np…` query (reviewer: the picker reopened
        // mid-token).
        val text = "hey @$aliceNpub "
        // Just after "@np".
        assertNull(MentionComposer.activeMentionQuery(text, caret = 7))
        // Just after "@n".
        assertNull(MentionComposer.activeMentionQuery(text, caret = 6))
        // Deeper inside the body.
        assertNull(MentionComposer.activeMentionQuery(text, caret = 20))
    }

    @Test
    fun caretJustPastInsertedChipDoesNotReopenQuery() {
        // Right edge of the chip (before the trailing space) is still the chip,
        // not a query.
        val chip = "@$aliceNpub"
        val text = "hey $chip "
        assertNull(MentionComposer.activeMentionQuery(text, caret = ("hey ").length + chip.length))
    }

    // --- filter -------------------------------------------------------------

    @Test
    fun emptyQueryReturnsAllInOrder() {
        assertEquals(candidates, MentionComposer.filter("", candidates))
    }

    @Test
    fun filtersByDisplayNameCaseInsensitiveContains() {
        assertEquals(listOf(bob), MentionComposer.filter("rob", candidates))
        assertEquals(listOf(alice), MentionComposer.filter("ALI", candidates))
    }

    @Test
    fun filtersByNip05LocalPartPrefix() {
        // "bob" matches Bob's nip05 local part "bobby" (prefix) but NOT the
        // domain, and not Alice.
        assertEquals(listOf(bob), MentionComposer.filter("bob", candidates))
    }

    @Test
    fun nip05DomainDoesNotMatch() {
        // "example" only appears in Alice's nip05 domain, which is not matched.
        assertTrue(MentionComposer.filter("example", candidates).isEmpty())
    }

    @Test
    fun filtersByNpubBody() {
        // The body after npub1 is all "q" for Alice, all "p" for Bob.
        assertEquals(listOf(alice), MentionComposer.filter("qqqq", candidates))
        assertEquals(listOf(bob), MentionComposer.filter("pppp", candidates))
    }

    @Test
    fun preservesInputOrderForTies() {
        val both = MentionComposer.filter("", listOf(bob, alice))
        assertEquals(listOf(bob, alice), both)
    }

    // --- insertMention ------------------------------------------------------

    @Test
    fun insertReplacesQueryWithChipAndTrailingSpace() {
        val text = "hey @al"
        val active = MentionComposer.activeMentionQuery(text, text.length)!!
        val result = MentionComposer.insertMention(text, active, alice)
        assertEquals("hey @$aliceNpub ", result.text)
        // Caret lands just past the trailing space.
        assertEquals(result.text.length, result.selection)
    }

    @Test
    fun insertMidSentenceDoesNotDoubleSpace() {
        val text = "hi @al there"
        // Caret sits right after "@al" (offset 6).
        val active = MentionComposer.activeMentionQuery(text, caret = 6)!!
        val result = MentionComposer.insertMention(text, active, alice)
        // Existing space before "there" is reused — no double space.
        assertEquals("hi @$aliceNpub there", result.text)
        assertEquals(("hi @$aliceNpub").length, result.selection)
    }

    @Test
    fun insertFromBareAt() {
        val text = "@"
        val active = MentionComposer.activeMentionQuery(text, 1)!!
        val result = MentionComposer.insertMention(text, active, bob)
        assertEquals("@$bobNpub ", result.text)
    }

    // --- wholeChipBackspace -------------------------------------------------

    @Test
    fun backspaceAtChipRightEdgeDeletesWholeChip() {
        val chip = "@$aliceNpub"
        val oldText = "hey $chip"
        val oldCaret = oldText.length
        // The IME's single-char deletion: drop the last char, caret back one.
        val newText = oldText.dropLast(1)
        val newCaret = oldCaret - 1
        val result = MentionComposer.wholeChipBackspace(oldText, oldCaret, newText, newCaret)
        assertEquals("hey ", result!!.text)
        assertEquals("hey ".length, result.selection)
    }

    @Test
    fun firstBackspaceAfterInsertionDeletesChipAndTrailingSpace() {
        // Reviewer's case: immediately after insertMention the caret sits past
        // the trailing space (`@npub1… ▮`). The first Backspace must delete the
        // whole chip + the space in one keypress, not just the space.
        val text = "hey @al"
        val active = MentionComposer.activeMentionQuery(text, text.length)!!
        val inserted = MentionComposer.insertMention(text, active, alice)
        // Sanity: the caret really is past the trailing space.
        assertEquals("hey @$aliceNpub ", inserted.text)
        assertEquals(inserted.text.length, inserted.selection)
        // The IME single-char Backspace removes the trailing space.
        val oldText = inserted.text
        val oldCaret = inserted.selection
        val newText = oldText.dropLast(1)
        val newCaret = oldCaret - 1
        val result = MentionComposer.wholeChipBackspace(oldText, oldCaret, newText, newCaret)
        // Whole chip + space gone in one keypress.
        assertEquals("hey ", result!!.text)
        assertEquals("hey ".length, result.selection)
    }

    @Test
    fun backspacingTrailingSpaceNotAfterChipIsNotIntercepted() {
        // A trailing space after plain text (no chip) is deleted verbatim.
        val oldText = "hello "
        val result = MentionComposer.wholeChipBackspace(oldText, 6, "hello", 5)
        assertNull(result)
    }

    @Test
    fun backspaceInPlainTextIsNotIntercepted() {
        val oldText = "hello"
        val result = MentionComposer.wholeChipBackspace(oldText, 5, "hell", 4)
        assertNull(result)
    }

    @Test
    fun backspaceNotAtChipEdgeIsNotIntercepted() {
        // Caret in the middle of plain text following a chip.
        val chip = "@$aliceNpub"
        val oldText = "$chip world"
        // Delete the 'd' at the very end — not a chip edge.
        val result = MentionComposer.wholeChipBackspace(oldText, oldText.length, oldText.dropLast(1), oldText.length - 1)
        assertNull(result)
    }

    @Test
    fun multiCharEditIsNotTreatedAsBackspace() {
        val chip = "@$aliceNpub"
        val oldText = "x$chip"
        // A two-char shrink isn't a single Backspace.
        val result = MentionComposer.wholeChipBackspace(oldText, oldText.length, "x", 1)
        assertNull(result)
    }

    // --- chipRanges ---------------------------------------------------------

    @Test
    fun chipRangesFindsWordBoundaryChips() {
        val chip = "@$aliceNpub"
        val text = "hi $chip and $chip!"
        val ranges = MentionComposer.chipRanges(text)
        assertEquals(2, ranges.size)
        // Each range covers exactly the chip text.
        ranges.forEach { r -> assertEquals(chip, text.substring(r.first, r.last + 1)) }
    }

    @Test
    fun chipRangesIgnoresAtInsideWord() {
        // `foo@npub1…` is not a word-boundary chip.
        val text = "foo@$aliceNpub"
        assertTrue(MentionComposer.chipRanges(text).isEmpty())
    }

    // --- visualText ----------------------------------------------------------

    @Test
    fun visualTextShowsCandidateDisplayNameButKeepsOriginalOffsets() {
        val text = "hey @$aliceNpub now"
        val visual = MentionComposer.visualText(text, candidates)

        assertEquals("hey @Alice now", visual.text)
        assertEquals(text.length, visual.transformedToOriginal(visual.text.length))
        assertEquals("hey @Alice".length, visual.originalToTransformed("hey @$aliceNpub".length))
        assertEquals("hey @$aliceNpub".length, visual.transformedToOriginal("hey @Alice".length))
    }

    @Test
    fun visualTextFallsBackToShortNpubForUnresolvedChip() {
        val text = "hey @$aliceNpub"
        val visual = MentionComposer.visualText(text, emptyList())

        assertEquals("hey @npub1qqq...", visual.text)
        assertTrue(visual.text.length < text.length)
    }

    @Test
    fun visualTextDoesNotRenderFullNpubAsDisplayName() {
        val rawNpubCandidate = alice.copy(displayName = aliceNpub)
        val visual = MentionComposer.visualText("@$aliceNpub", listOf(rawNpubCandidate))

        assertEquals("@npub1qqq...", visual.text)
    }

    @Test
    fun visualTextKeepsNpubPrefixedDisplayNameThatIsNotRawNpub() {
        val npubNamedCandidate = alice.copy(displayName = "npub1_collector_of_things")
        val visual =
            MentionComposer.visualText(
                "@$aliceNpub",
                MentionComposer.candidatesByNpub(listOf(npubNamedCandidate)),
            )

        assertEquals("@npub1_collector_of_things", visual.text)
    }

    // --- clampSelectionOutOfChips -------------------------------------------

    @Test
    fun clampPushesCaretOutOfChipInteriorToNearerEdge() {
        val chip = "@$aliceNpub"
        val text = "hey $chip rest"
        val chipStart = "hey ".length
        val chipEnd = chipStart + chip.length // one-past the chip
        // A caret 2 chars into the chip snaps back to the left edge.
        val nearLeft = MentionComposer.clampSelectionOutOfChips(text, chipStart + 2, chipStart + 2)
        assertEquals(chipStart, nearLeft.start)
        assertEquals(chipStart, nearLeft.end)
        // A caret 2 chars before the chip end snaps forward to the right edge.
        val nearRight = MentionComposer.clampSelectionOutOfChips(text, chipEnd - 2, chipEnd - 2)
        assertEquals(chipEnd, nearRight.start)
        assertEquals(chipEnd, nearRight.end)
    }

    @Test
    fun clampLeavesChipEdgesUntouched() {
        val chip = "@$aliceNpub"
        val text = "hey $chip rest"
        val chipStart = "hey ".length
        val chipEnd = chipStart + chip.length
        // Left edge stays.
        assertEquals(
            MentionComposer.Selection(chipStart, chipStart),
            MentionComposer.clampSelectionOutOfChips(text, chipStart, chipStart),
        )
        // Right edge stays.
        assertEquals(
            MentionComposer.Selection(chipEnd, chipEnd),
            MentionComposer.clampSelectionOutOfChips(text, chipEnd, chipEnd),
        )
    }

    @Test
    fun clampLeavesPlainTextCaretsUntouched() {
        val text = "no chips here"
        assertEquals(
            MentionComposer.Selection(3, 7),
            MentionComposer.clampSelectionOutOfChips(text, 3, 7),
        )
    }

    @Test
    fun clampSnapsBothSelectionEndpointsIndependently() {
        val chip = "@$aliceNpub"
        val text = "$chip and $chip"
        val firstStart = 0
        val secondStart = "$chip and ".length
        val secondEnd = secondStart + chip.length
        // A selection that starts inside the first chip near its left edge and
        // ends inside the second chip near its right edge: each endpoint snaps
        // to its own nearer edge.
        val clamped = MentionComposer.clampSelectionOutOfChips(text, firstStart + 1, secondEnd - 1)
        assertEquals(firstStart, clamped.start)
        assertEquals(secondEnd, clamped.end)
    }
}
