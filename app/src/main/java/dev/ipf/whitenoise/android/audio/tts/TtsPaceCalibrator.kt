package dev.ipf.whitenoise.android.audio.tts

import kotlin.math.abs

/** What one call to [TtsPaceCalibrator.observe] did with the sample it was given. */
internal enum class TtsPaceObservation {
    /** Too short, or outside the plausible band: nothing was learned. */
    Rejected,

    /** Believed and blended in, but the estimate did not move enough to notice. */
    Accepted,

    /** Believed, blended in, and the estimate moved. */
    Moved,
}

/**
 * Learns how fast the installed voice actually speaks.
 *
 * The estimated word highlight is only as good as its idea of the speaking
 * rate, and a fixed constant cannot be right for every voice. What a sample is
 * — and why an utterance's own start-to-done duration is not a good one — lives
 * in [ttsPaceOutcomeOf]; this class only decides how much of a sample to
 * believe. Callback scheduling can add slack, so short samples are rejected and
 * each accepted sample is damped and clamped rather than trusted outright.
 *
 * [observe] distinguishes "rejected" from "accepted but unmoved" because the
 * caller needs both answers for different questions: whether the estimate
 * changed, and whether this voice has been measured at all. Collapsing them
 * into one Boolean means a voice whose true pace is within a hair of the value
 * already held is never recorded as measured, and re-measures from nothing in
 * every future process.
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
     * count — and [elapsedMs] is the audible time that payload took at [rate].
     */
    @Suppress("ReturnCount")
    fun observe(
        unitCount: Int,
        elapsedMs: Long,
        rate: Float,
    ): TtsPaceObservation {
        if (unitCount < MIN_SAMPLE_UNITS || elapsedMs < MIN_SAMPLE_MS) return TtsPaceObservation.Rejected
        val at1x = elapsedMs * TtsWordTimingEstimate.clampRate(rate) / unitCount
        if (at1x < MIN_MS_PER_UNIT || at1x > MAX_MS_PER_UNIT) return TtsPaceObservation.Rejected
        val blended = msPerUnitAt1x * (1 - WEIGHT) + at1x * WEIGHT
        val next = blended.coerceIn(MIN_MS_PER_UNIT, MAX_MS_PER_UNIT)
        val moved = abs(next - msPerUnitAt1x) > MOVE_THRESHOLD
        msPerUnitAt1x = next
        return if (moved) TtsPaceObservation.Moved else TtsPaceObservation.Accepted
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

        /**
         * A wall-clock floor against callback jitter. It is deliberately NOT
         * rate-normalised: it guards against scheduling slack, which is real
         * time and does not shrink because the voice is speaking faster. Each
         * caller states its own accuracy budget on top of this.
         */
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
