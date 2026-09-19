package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which parts of a message a search marks, before any of it is painted. */
class MessageSearchHighlightTest {
    /** Every occurrence is marked, not only the first, and matching ignores case. */
    @Test
    fun everyOccurrenceIsMarkedRegardlessOfCase() {
        val ranges = messageSearchMatchRanges("Ferry on the ferry, FERRY", "ferry")

        assertEquals(listOf(0 until 5, 13 until 18, 20 until 25), ranges)
    }

    /** A needle with stray spaces still marks the word the user meant. */
    @Test
    fun theNeedleIsTrimmedBeforeMatching() {
        assertEquals(listOf(6 until 11), messageSearchMatchRanges("hello world", "  world "))
    }

    /** Overlapping candidates never produce overlapping marks. */
    @Test
    fun repeatsDoNotProduceOverlappingMarks() {
        val ranges = messageSearchMatchRanges("aaaa", "aa")

        assertEquals(listOf(0 until 2, 2 until 4), ranges)
    }

    /** Nothing to mark stays nothing: no query, no body, no match. */
    @Test
    fun absentOrEmptyInputsMarkNothing() {
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges("hello", ""))
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges("hello", "   "))
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges("", "hello"))
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges("hello", "goodbye"))
    }

    /** A needle longer than the body cannot match and must not read past its end. */
    @Test
    fun aNeedleLongerThanTheBodyMarksNothing() {
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges("hi", "hello there"))
    }

    /**
     * Lowercasing some scripts changes the string's length, which would place every mark on the
     * wrong characters. Those bodies are left unmarked rather than marked incorrectly.
     */
    @Test
    fun bodiesWhoseLengthChangesWhenLowercasedAreLeftUnmarked() {
        val body = "İstanbul"

        assertEquals(body.length, body.length)
        assertEquals(emptyList<IntRange>(), messageSearchMatchRanges(body, "stan"))
    }

    /** Only the rows search actually counted may be marked, whatever their text happens to say. */
    @Test
    fun onlyCountedRowsAreMarked() {
        val marking = ConversationSearchMarking(needle = "ferry", matchedMessageIds = setOf("a", "b"))

        assertEquals(true, marking.marks("a"))
        assertEquals(true, marking.marks("b"))
        assertEquals("a row the arrows cannot reach is never marked", false, marking.marks("c"))
    }
}
