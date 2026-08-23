package dev.ipf.whitenoise.android.audio.tts

import kotlin.math.abs

/**
 * Learns how fast the installed voice actually speaks.
 *
 * The estimated word highlight is only as good as its idea of the speaking
 * rate, and a fixed constant cannot be right for every voice. Each utterance's
 * start-to-done duration is one sample: the engine plays utterances serially,
 * so the span between `onStart` and `onDone` of the same utterance is the time
 * its audio took.
 *
 * UNITS: everything here is milliseconds per SPEECH UNIT — a tenth of a
 * syllable, the unit [TtsWordTimingEstimate] weighs words in. The rate must be
 * learned per the same unit it is spent in: a rate learned per raw character
 * while the plan spends per weighted unit runs systematically slow on every
 * digit- or acronym-carrying sentence, compounding toward the end.
 */
internal class TtsPaceCalibrator(
    initialMsPerUnitAt1x: Double = TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X,
) {
    var msPerUnitAt1x: Double = initialMsPerUnitAt1x.coerceIn(MIN_MS_PER_UNIT, MAX_MS_PER_UNIT)
        private set

    /**
     * Feeds in one measured utterance. [unitCount] is the payload's weighted
     * length from [TtsWordTimingEstimate.weightedLengthOf] — never a character
     * count. Returns true when the estimate moved, so the caller knows there
     * is something worth persisting.
     */
    @Suppress("ReturnCount")
    fun observe(
        unitCount: Int,
        elapsedMs: Long,
        rate: Float,
    ): Boolean {
        if (unitCount < MIN_SAMPLE_UNITS || elapsedMs < MIN_SAMPLE_MS) return false
        val at1x = elapsedMs * TtsWordTimingEstimate.clampRate(rate) / unitCount
        if (at1x < MIN_MS_PER_UNIT || at1x > MAX_MS_PER_UNIT) return false
        val blended = msPerUnitAt1x * (1 - WEIGHT) + at1x * WEIGHT
        val next = blended.coerceIn(MIN_MS_PER_UNIT, MAX_MS_PER_UNIT)
        val moved = abs(next - msPerUnitAt1x) > MOVE_THRESHOLD
        msPerUnitAt1x = next
        return moved
    }

    /** A different engine is a different voice; forget what the old one taught. */
    fun reset(msPerUnitAt1x: Double = TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X) {
        this.msPerUnitAt1x = msPerUnitAt1x.coerceIn(MIN_MS_PER_UNIT, MAX_MS_PER_UNIT)
    }

    private companion object {
        /**
         * How much of one measurement to believe. Low, because a single
         * utterance's sample can be stretched by anything the device happens
         * to be doing.
         */
        const val WEIGHT = 0.25

        /** Below ~6 syllables an utterance is too short for its duration to say much. */
        const val MIN_SAMPLE_UNITS = 60
        const val MIN_SAMPLE_MS = 400L

        /**
         * The band the learned rate may occupy, around the 17.5 default: ~90 ms
         * to ~400 ms per syllable at 1x covers every plausible system voice,
         * while a measurement outside it is a corrupted sample, not a discovery.
         */
        const val MIN_MS_PER_UNIT = 9.0
        const val MAX_MS_PER_UNIT = 40.0

        const val MOVE_THRESHOLD = 0.1
    }
}
