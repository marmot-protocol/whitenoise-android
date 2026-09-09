package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Semantic segmentation: protected tokens and role boundaries decide sentence
 * cuts, every eligible source character stays covered, and one-word sentences
 * are never filtered out.
 */
class SpeechSentenceSegmenterTest {
    @Test
    fun everyOneWordAnswerIsExactlyOneEligibleSentence() {
        listOf("Yes.", "No!", "OK", "I.", "A.", "7", "Done", "Hello 👋").forEach { source ->
            assertEquals("'$source' must stay one sentence", 1, segment(source).size)
        }
    }

    @Test
    fun aRunOfOneWordAnswersKeepsEveryOneOfThem() {
        val source = "Yes. No! OK. Done."

        val sentences = segment(source)

        assertEquals(listOf("Yes.", "No!", "OK.", "Done."), sentences.map { source.slice(it) })
        assertEquals(listOf(0, 1, 2, 3), sentences.map(SpeechSentence::ordinal))
    }

    @Test
    fun theLastWordWithoutTerminalPunctuationIsStillASentence() {
        val source = "Almost there Done"

        val sentences = segment(source)

        assertEquals(1, sentences.size)
        assertEquals(source.length, sentences.single().source.end)
    }

    @Test
    fun titleAndProseAbbreviationsDoNotCutASentence() {
        val source = "Dr. Smith arrived at 7 a.m. Then left."

        assertEquals(listOf("Dr. Smith arrived at 7 a.m.", "Then left."), segment(source).map { source.slice(it) })
    }

    @Test
    fun anAbbreviationAtARealSentenceEndStillEndsTheSentence() {
        val source = "It ships to the U.S. Prices vary."

        assertEquals(listOf("It ships to the U.S.", "Prices vary."), segment(source).map { source.slice(it) })
    }

    @Test
    fun standaloneNumberAbbreviationDependsOnItsContext() {
        assertEquals(2, segment("No. Next.").size)
        assertEquals(1, segment("No. 5 is ready.").size)
    }

    @Test
    fun protectedDecimalsAndCurrencyDoNotCreateSentenceBoundaries() {
        val source = "Pay \$12.50 and 0.05% now. Then stop."

        val sentences = segment(source)

        assertEquals(2, sentences.size)
        assertTrue(source.slice(sentences.first()).contains("\$12.50"))
        assertTrue(source.slice(sentences.first()).contains("0.05%"))
    }

    @Test
    fun protectedDatesAndTimestampsDoNotCreateSentenceBoundaries() {
        val source = "Due 2026-09-08T14:05:09Z. Ship it."

        val sentences = segment(source)

        assertEquals(2, sentences.size)
        assertTrue(source.slice(sentences.first()).contains("2026-09-08T14:05:09Z"))
    }

    @Test
    fun crlfAndQuoteTerminatorsAreHandled() {
        assertEquals(2, segment("First.\r\nSecond.").size)
        assertEquals(2, segment("“Stop.” Then go.").size)
    }

    @Test
    fun codeLinesAreTheirOwnLogicalNavigationUnits() {
        val source = "if ready:\n    send()\nstop()"

        val sentences = segment(source, role = SpeechRole.CodeBlock)

        assertEquals(3, sentences.size)
        assertEquals(listOf(SpeechRole.CodeBlock), sentences.map(SpeechSentence::role).distinct())
    }

    @Test
    fun segmentationCoversEverySourceCharacterExactlyOnce() {
        val source = "Dr. Smith paid $12.50 on 2026-09-08. Yes! OK\nDone"

        val sentences = segment(source)

        var cursor = 0
        sentences.forEach { sentence ->
            assertTrue("sentences must be ordered and disjoint", sentence.source.start >= cursor)
            assertTrue(
                "only whitespace may fall between sentences",
                source.substring(cursor, sentence.source.start).isBlank(),
            )
            cursor = sentence.source.end
        }
        assertTrue(source.substring(cursor).isBlank())
    }

    @Test
    fun repeatedIdenticalSentencesGetDistinctRevisionScopedIds() {
        val sentences = segment("Done. Done.", revisionId = "rev-1")

        assertEquals(2, sentences.size)
        assertEquals(2, sentences.map(SpeechSentence::id).distinct().size)
    }

    @Test
    fun logicalIdsAreStableForTheSameSourceAndRevision() {
        val first = segment("Yes. No.", revisionId = "rev-1").map(SpeechSentence::id)
        val second = segment("Yes. No.", revisionId = "rev-1").map(SpeechSentence::id)
        val otherRevision = segment("Yes. No.", revisionId = "rev-2").map(SpeechSentence::id)

        assertEquals(first, second)
        assertEquals(emptyList<String>(), first.intersect(otherRevision.toSet()).toList())
    }

    private fun segment(
        source: String,
        role: SpeechRole = SpeechRole.Prose,
        revisionId: String = "rev-1",
    ): List<SpeechSentence> =
        SpeechSentenceSegmenter.segment(
            source = source,
            role = role,
            context = SpeechContext(voiceLocale = Locale.US),
            revisionId = revisionId,
        )

    private fun String.slice(sentence: SpeechSentence): String = substring(sentence.source.start, sentence.source.end)
}
