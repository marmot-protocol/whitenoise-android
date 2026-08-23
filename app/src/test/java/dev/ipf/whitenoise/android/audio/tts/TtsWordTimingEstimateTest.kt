package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.BreakIterator
import java.util.Locale

class TtsWordTimingEstimateTest {
    @Test
    fun planCoversEveryWordInOrderWithMonotonicStarts() {
        val text = "The quick brown fox jumps over the lazy dog."
        val plan = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)

        assertEquals(9, plan.size)
        assertEquals("The", text.substring(plan[0].start, plan[0].end))
        assertEquals("dog", text.substring(plan[8].start, plan[8].end))
        assertEquals(0L, plan[0].startMs)
        for (index in 1 until plan.size) {
            assertTrue(plan[index].startMs > plan[index - 1].startMs)
        }
    }

    @Test
    fun planRangesAreCompleteBreakIteratorWords() {
        val text = "Hello, world! It's a well-known trap (really)."
        val plan = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)
        val iterator = BreakIterator.getWordInstance(Locale.US).apply { setText(text) }

        assertTrue(plan.isNotEmpty())
        for (word in plan) {
            assertTrue(iterator.isBoundary(word.start))
            assertEquals(word.end, iterator.following(word.start))
        }
    }

    @Test
    fun punctuationIsTrimmedFromHighlightRanges() {
        val text = "Wait, stop."
        val plan = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)

        assertEquals(listOf("Wait", "stop"), plan.map { text.substring(it.start, it.end) })
    }

    @Test
    fun multiWordTokensShareTheirTimeAmongTheirWords() {
        // Whether "well-known" is one iterator word or two differs between the
        // JVM and ICU; the contract is that every iterator word inside the
        // token is scheduled, in order, within the token's slot.
        val text = "a well-known trap"
        val plan = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)
        val words = plan.map { text.substring(it.start, it.end) }
        val iterator = BreakIterator.getWordInstance(Locale.US).apply { setText(text) }
        val expected = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            if (piece.any(Char::isLetterOrDigit)) expected += piece
            start = end
            end = iterator.next()
        }

        assertEquals(expected, words)
        for (index in 1 until plan.size) {
            assertTrue(plan[index].startMs >= plan[index - 1].startMs)
        }
    }

    @Test
    fun symbolOnlyTokensAdvanceTimeWithoutEmittingAWord() {
        val text = "Settings > Voice"
        val plan = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)

        assertEquals(listOf("Settings", "Voice"), plan.map { text.substring(it.start, it.end) })
    }

    @Test
    fun higherRateCompressesTheSchedule() {
        val text = "The quick brown fox jumps over the lazy dog."
        val slow = TtsWordTimingEstimate.plan(text, Locale.US, rate = 1.0f)
        val fast = TtsWordTimingEstimate.plan(text, Locale.US, rate = 2.0f)

        assertTrue(fast.last().startMs < slow.last().startMs)
    }

    @Test
    fun digitsCostMoreThanTheirCharacterCount() {
        val year = TtsWordTimingEstimate.spokenWeightOf("1984")
        val word = TtsWordTimingEstimate.spokenWeightOf("nine")
        assertTrue(year > word * 2)
    }

    @Test
    fun acronymsAreChargedLetterNames() {
        val acronym = TtsWordTimingEstimate.spokenWeightOf("MLS")
        val word = TtsWordTimingEstimate.spokenWeightOf("mls")
        assertTrue(acronym > word)
    }

    @Test
    fun shoutedTextIsProseNotAStringOfAcronyms() {
        val shouted = "THIS ENTIRE MESSAGE IS SHOUTED PROSE TODAY"
        val calm = shouted.lowercase(Locale.US)
        val shoutedLength = TtsWordTimingEstimate.weightedLengthOf(shouted)
        val calmLength = TtsWordTimingEstimate.weightedLengthOf(calm)

        assertEquals(calmLength.toDouble(), shoutedLength.toDouble(), calmLength * 0.1)
    }

    @Test
    fun dottedInitialismsAreSpelledOut() {
        assertTrue(TtsWordTimingEstimate.isDottedInitialism("U.S."))
        assertTrue(TtsWordTimingEstimate.isDottedInitialism("a.m."))
        assertTrue(!TtsWordTimingEstimate.isDottedInitialism("etc."))
        assertTrue(!TtsWordTimingEstimate.isDottedInitialism("U.S"))
    }

    @Test
    fun abbreviationsAreChargedTheirSpokenExpansion() {
        val doctor = TtsWordTimingEstimate.spokenWeightOf("Dr.")
        val forExample = TtsWordTimingEstimate.spokenWeightOf("e.g.")
        assertEquals(20, doctor)
        assertEquals(40, forExample)
    }

    @Test
    fun clausePunctuationCarriesAPause() {
        assertTrue(TtsWordTimingEstimate.pauseWeightOf("wait,") > 0)
        assertTrue(TtsWordTimingEstimate.pauseWeightOf("here:") > TtsWordTimingEstimate.pauseWeightOf("wait,"))
        assertTrue(TtsWordTimingEstimate.pauseWeightOf("done.") == 0)
        // A closing quote may trail the punctuation that carries the pause.
        assertTrue(TtsWordTimingEstimate.pauseWeightOf("\"wait,\"") > 0)
    }

    @Test
    fun weightedLengthMatchesTheSumOfThePlan() {
        val text = "Numbers like 1984 and acronyms like MLS expand, right?"
        val tokens = TtsWordTimingEstimate.weightedTokens(text)
        val total = tokens.sumOf { it.speechWeight + it.pauseWeight }

        assertEquals(total, TtsWordTimingEstimate.weightedLengthOf(text))
    }

    @Test
    fun blankTextHasNoPlan() {
        assertTrue(TtsWordTimingEstimate.plan("   ", Locale.US, rate = 1.0f).isEmpty())
        assertEquals(0, TtsWordTimingEstimate.weightedLengthOf("   "))
    }
}
