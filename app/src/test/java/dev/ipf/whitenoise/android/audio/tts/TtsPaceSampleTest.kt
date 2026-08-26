package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every refusal test that CAN carry a witness does: the same gap with only the
 * offending fact corrected is asserted to measure, and to move the learned rate
 * in a named direction. Without that, a rule could tighten until it refuses
 * everything and every test here would still pass. The one exception is
 * NO_OPENER, which has nothing to correct, and says so where it is asserted.
 *
 * Scope, stated honestly: these are tests of the decision, not of the wiring.
 * They build openers directly, so they prove what the rule does with a given
 * state, never that the controller can reach that state from real callbacks.
 * That is what the lane tests in TtsEstimatedTimingLaneTest are for.
 */
class TtsPaceSampleTest {
    @Test
    fun aGapBetweenTwoConsecutiveUtterancesMeasuresTheOpener() {
        val sample = measured()

        assertEquals(OPENER_UNITS, sample.units)
        assertEquals(GAP_MS - SENTENCE_SEAM_MS, sample.elapsedMs)
        assertEquals(1.0f, sample.rate, 0.0f)
    }

    @Test
    fun aSentenceBoundaryIsChargedABreathAMidSentenceSplitIsNot() {
        val afterSentence = measured(opener = opener(endsSentence = true))
        val afterHardSplit = measured(opener = opener(endsSentence = false))

        assertEquals(
            TTS_SENTENCE_BREATH_MS,
            afterHardSplit.elapsedMs - afterSentence.elapsedMs,
        )
    }

    @Test
    fun theBreathShrinksWithThePlaybackRateButTheHandoverDoesNot() {
        // The breath is audio and is spoken faster; the handover is the engine's
        // own machinery between two utterances and is not. Subtracting a fixed
        // seam at speed removes more silence than was there, and
        // over-subtracting teaches a rate that is too fast.
        val atOneX = measured(opener = opener(rate = 1.0f))
        val atTwoX = measured(opener = opener(rate = 2.0f))

        assertEquals(TTS_SENTENCE_BREATH_MS / 2, atTwoX.elapsedMs - atOneX.elapsedMs)
    }

    @Test
    fun anOrdinarySentenceStillMeasuresAtHighPlaybackRates() {
        // Roughly eight words of chat at 3x: 150 units of speech from a voice a
        // little slower than the seed, plus a seam of 120 + 100/3.
        val fast = opener(units = 150, rate = 3.0f)
        val audibleMs = 1_100L
        val seamMs = TTS_UTTERANCE_HANDOVER_MS + TTS_SENTENCE_BREATH_MS / 3

        val sample = measured(opener = fast, startingAtMs = OPENER_STARTED_AT_MS + audibleMs + seamMs)

        assertEquals(audibleMs, sample.elapsedMs)
        assertWouldHaveMoved(sample, Direction.Slower)
    }

    @Test
    fun aVeryShortSentenceAtHighPlaybackRateIsRefusedRatherThanGuessed() {
        // Four words at 3x is ~350 ms of audio against a ~153 ms seam. Through
        // a handover that does not shrink with the rate, a gap that short
        // genuinely cannot say how fast the voice is; the in-process bootstrap
        // is what keeps this listener from having no rate at all.
        val fast = opener(units = SHORT_UNITS, rate = 3.0f)

        assertEquals(
            TtsPaceRefusal.SEAM_WOULD_DOMINATE,
            refusal(opener = fast, startingAtMs = OPENER_STARTED_AT_MS + 350 + 153),
        )
    }

    @Test
    fun aGapWithNoOpenerMeasuresNothing() {
        // No witness is possible or wanted here: there is nothing to correct.
        assertEquals(TtsPaceRefusal.NO_OPENER, refusal(opener = null))
        assertEquals(TtsPaceRefusal.NO_OPENER, refusal(opener = opener(units = 0)))
    }

    @Test
    fun anOpenerThatNeverCompletedIsRefused() {
        assertEquals(
            TtsPaceRefusal.OPENER_NEVER_COMPLETED,
            refusal(opener = opener(completed = false)),
        )
        assertWouldHaveMoved(measured(), Direction.Slower)
    }

    @Test
    fun aGapAcrossAReplacedEngineQueueIsRefused() {
        assertEquals(
            TtsPaceRefusal.ENGINE_QUEUE_REPLACED,
            refusal(startingEpoch = EPOCH + 1),
        )
        assertWouldHaveMoved(measured(), Direction.Slower)
    }

    @Test
    fun aGapThatSkippedAChunkIsRefused() {
        assertEquals(
            TtsPaceRefusal.NOT_THE_NEXT_CHUNK,
            refusal(startingChunkIndex = OPENER_CHUNK_INDEX + 2),
        )
        assertWouldHaveMoved(measured(), Direction.Slower)
    }

    @Test
    fun theFirstUtteranceAnEngineSpeaksIsRefused() {
        assertEquals(
            TtsPaceRefusal.OPENER_WAS_FIRST_SPOKEN,
            refusal(opener = opener(wasFirstSpokenByEngine = true)),
        )
        assertWouldHaveMoved(measured(), Direction.Slower)
    }

    @Test
    fun aSuccessorQueuedAfterTheOpenerFinishedIsRefused() {
        // The queue held nothing past the opener when it completed, so whatever
        // starts next was appended into an engine that had already run dry.
        assertEquals(
            TtsPaceRefusal.SUCCESSOR_QUEUED_AFTER_OPENER_DONE,
            refusal(opener = opener(chunkCountAtCompletion = OPENER_CHUNK_INDEX + 1)),
        )
        assertWouldHaveMoved(measured(), Direction.Slower)
    }

    @Test
    fun aGapTooShortForItsSeamToBeNoiseIsRefused() {
        val shortOpener = opener(units = SHORT_UNITS)
        val tooShort = OPENER_STARTED_AT_MS + SENTENCE_SEAM_MS * MIN_SEAM_SIGNIFICANCE - 1

        assertEquals(
            TtsPaceRefusal.SEAM_WOULD_DOMINATE,
            refusal(opener = shortOpener, startingAtMs = tooShort),
        )
        // Witness: one millisecond more and it measures, and that measurement
        // really would have pulled the rate faster.
        assertWouldHaveMoved(
            measured(opener = shortOpener, startingAtMs = tooShort + 1),
            Direction.Faster,
        )
    }

    @Test
    fun closingPunctuationDoesNotHideASentenceEnding() {
        assertTrue(ttsUtteranceEndsSentence("He said \"stop.\""))
        assertTrue(ttsUtteranceEndsSentence("Really? "))
        assertTrue(ttsUtteranceEndsSentence("(Wait!)"))
        assertTrue(!ttsUtteranceEndsSentence("the quick brown fox jumps"))
        assertTrue(!ttsUtteranceEndsSentence(""))
    }

    private enum class Direction { Faster, Slower }

    private fun assertWouldHaveMoved(
        sample: TtsPaceSample,
        direction: Direction,
    ) {
        val calibrator = TtsPaceCalibrator()
        assertEquals(
            TtsPaceObservation.Moved,
            calibrator.observe(sample.units, sample.elapsedMs, sample.rate),
        )
        val seed = TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X
        when (direction) {
            Direction.Faster -> assertTrue(calibrator.msPerUnitAt1x < seed)
            Direction.Slower -> assertTrue(calibrator.msPerUnitAt1x > seed)
        }
    }

    private fun opener(
        units: Int = OPENER_UNITS,
        rate: Float = 1.0f,
        endsSentence: Boolean = true,
        wasFirstSpokenByEngine: Boolean = false,
        completed: Boolean = true,
        chunkCountAtCompletion: Int = OPENER_CHUNK_INDEX + 3,
    ) = TtsPaceGapOpener(
        epoch = EPOCH,
        chunkIndex = OPENER_CHUNK_INDEX,
        startedAtMs = OPENER_STARTED_AT_MS,
        rate = rate,
        units = units,
        endsSentence = endsSentence,
        wasFirstSpokenByEngine = wasFirstSpokenByEngine,
        completed = completed,
        chunkCountAtCompletion = chunkCountAtCompletion,
    )

    private fun outcome(
        opener: TtsPaceGapOpener? = opener(),
        startingEpoch: Long = EPOCH,
        startingChunkIndex: Int = OPENER_CHUNK_INDEX + 1,
        startingAtMs: Long = OPENER_STARTED_AT_MS + GAP_MS,
    ) = ttsPaceOutcomeOf(opener, startingEpoch, startingChunkIndex, startingAtMs)

    private fun refusal(
        opener: TtsPaceGapOpener? = opener(),
        startingEpoch: Long = EPOCH,
        startingChunkIndex: Int = OPENER_CHUNK_INDEX + 1,
        startingAtMs: Long = OPENER_STARTED_AT_MS + GAP_MS,
    ) = (outcome(opener, startingEpoch, startingChunkIndex, startingAtMs) as TtsPaceOutcome.Refused).reason

    private fun measured(
        opener: TtsPaceGapOpener? = opener(),
        startingAtMs: Long = OPENER_STARTED_AT_MS + GAP_MS,
    ) = (outcome(opener = opener, startingAtMs = startingAtMs) as TtsPaceOutcome.Measured).sample

    private companion object {
        const val EPOCH = 7L
        const val OPENER_CHUNK_INDEX = 3
        const val OPENER_STARTED_AT_MS = 10_000L

        /** Enough weight and enough gap that the base case lands mid-band, at ~24 ms/unit. */
        const val OPENER_UNITS = 200
        const val GAP_MS = 5_000L

        /** Small enough that the seam significance floor is what refuses, not the size floors. */
        const val SHORT_UNITS = 60

        val SENTENCE_SEAM_MS = TTS_UTTERANCE_HANDOVER_MS + TTS_SENTENCE_BREATH_MS
    }
}
