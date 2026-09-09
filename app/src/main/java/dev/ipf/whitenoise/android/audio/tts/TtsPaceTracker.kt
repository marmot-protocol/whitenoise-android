package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.state.StalenessGuard

/** Voice calibration evidence, scoped to the engine and its queue lifetimes. */
internal class TtsPaceTracker(
    private val timingStore: TtsTimingStore?,
    private val engineKey: () -> String,
    private val clock: () -> Long,
) {
    private val paceCalibrator = TtsPaceCalibrator()
    val msPerUnitAt1x: Double get() = paceCalibrator.msPerUnitAt1x

    // Pace measurement state. Its lifetime is deliberately NOT activeTiming's:
    // the opener has to survive its own onDone, because the gap it opened is
    // only closed by the NEXT utterance's onStart. Clearing it alongside
    // activeTiming would refuse every gap there is.
    //
    // engineQueueLifetime counts engine-queue replacements. The queue stops and
    // re-enqueues the engine on every disruptive path - start, pause, stop,
    // failure, and every requeue, which is also how a speech-rate change lands
    // - and it advances its own generation on exactly those paths. So an
    // opener stamped with the epoch its utterance was submitted under is
    // rejected by a single equality if anything replaced the queue in between.
    private val engineQueueLifetime = StalenessGuard()
    private var gapOpener: TtsPaceGapOpener? = null
    private var engineHasSpoken = false
    private var bootstrapRetired = false

    fun resetCalibration() {
        paceCalibrator.reset(storedPace())
    }

    fun onQueueReplaced() {
        engineQueueLifetime.advance()
    }

    fun onStart(
        chunk: TtsChunk,
        appliedRate: Float,
        startedAt: Long,
    ) {
        observePaceGap(chunk.index, startedAt)
        gapOpener =
            TtsPaceGapOpener(
                epoch = engineQueueLifetime.capture(),
                chunkIndex = chunk.index,
                startedAtMs = startedAt,
                rate = appliedRate,
                units = TtsWordTimingEstimate.weightedLengthOf(chunk.text),
                endsSentence = ttsUtteranceEndsSentence(chunk.text),
                wasFirstSpokenByEngine = !engineHasSpoken,
            )
        engineHasSpoken = true
    }

    fun onDone(
        chunk: TtsChunk,
        timing: ActiveUtteranceTiming?,
        chunkCount: Int,
    ) {
        closePaceGapOpener(chunk.index, chunkCount)
        if (timing != null) observeBootstrapPace(chunk, timing)
    }

    /** Loads the calibrated pace for the active engine voice, falling back to the safe default. */
    private fun storedPace(): Double {
        val stored = timingStore?.msPerUnitAt1x(engineKey())
        return stored ?: TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X
    }

    /** Starts a new engine-queue lifetime and clears voice-specific pace evidence. */
    fun resetMeasurements() {
        engineQueueLifetime.advance()
        gapOpener = null
        engineHasSpoken = false
        bootstrapRetired = false
    }

    /**
     * Records that the opener finished, and how many chunks the queue held when
     * it did. Read before the queue advances, so the count is the one that was
     * available to follow this utterance - which is what separates an auto-read
     * message appended behind a still-speaking opener from one appended long
     * after the queue ran dry.
     */
    private fun closePaceGapOpener(
        completedChunkIndex: Int,
        chunkCount: Int,
    ) {
        val opener = gapOpener ?: return
        if (!engineQueueLifetime.isCurrent(opener.epoch) || opener.chunkIndex != completedChunkIndex) return
        gapOpener = opener.copy(completed = true, chunkCountAtCompletion = chunkCount)
    }

    /**
     * Closes the gap the previous utterance opened, if it measured anything.
     *
     * Only a gap sample is persisted. The bootstrap below is allowed to steer
     * the estimate within this process, but the number written against a voice
     * has to be one this app can defend, and a bootstrap sample carries a
     * deduction nobody has measured. Because every accepted sample blends into
     * the same field, the calibrator is re-seeded from storage before the FIRST
     * gap is believed - otherwise the first thing persisted would be
     * three-quarters bootstrap. The re-seed is only kept if that gap is
     * actually accepted.
     */
    private fun observePaceGap(
        startingChunkIndex: Int,
        startingAtMs: Long,
    ) {
        val outcome =
            ttsPaceOutcomeOf(
                gapOpener,
                engineQueueLifetime.capture(),
                startingChunkIndex,
                startingAtMs,
            )
        val sample = (outcome as? TtsPaceOutcome.Measured)?.sample ?: return
        val bootstrapPace = paceCalibrator.msPerUnitAt1x
        if (!bootstrapRetired) paceCalibrator.reset(storedPace())
        val observation = paceCalibrator.observe(sample.units, sample.elapsedMs, sample.rate)
        if (observation == TtsPaceObservation.Rejected) {
            if (!bootstrapRetired) paceCalibrator.reset(bootstrapPace)
            return
        }
        bootstrapRetired = true
        // Persisted on ACCEPTANCE, not on movement: a voice whose pace already
        // matches the value held has still been measured, and a store that only
        // remembers changes forgets exactly those voices.
        timingStore?.setMsPerUnitAt1x(engineKey(), paceCalibrator.msPerUnitAt1x)
    }

    /**
     * The bootstrap lane: one utterance's own start-to-done interval, minus the
     * lead-in the estimate assumes.
     *
     * It is kept because a single-sentence message is one utterance and closes
     * no gap, so removing it would leave "read one message" permanently on the
     * seeded default. It is never persisted, and it is retired for good once a
     * gap has measured this engine, because the interval it uses contains an
     * engine-specific offset this process cannot see - see [ttsPaceOutcomeOf].
     *
     * Its guards are deliberately left exactly as they were. The deduction it
     * carries is worth least on a short utterance, and tightening the floor for
     * that is a real question - but it is a question about the lane this change
     * supersedes, and it cannot be asserted observably from here, so it is not
     * smuggled in untested.
     */
    private fun observeBootstrapPace(
        chunk: TtsChunk,
        timing: ActiveUtteranceTiming,
    ) {
        if (bootstrapRetired) return
        val elapsedSinceStart = clock() - timing.startedAt
        if (elapsedSinceStart <= TTS_ESTIMATED_AUDIO_LEAD_IN_MS) return
        paceCalibrator.observe(
            unitCount = TtsWordTimingEstimate.weightedLengthOf(chunk.text),
            elapsedMs = elapsedSinceStart - TTS_ESTIMATED_AUDIO_LEAD_IN_MS,
            rate = timing.rate,
        )
    }
}

internal data class ActiveUtteranceTiming(
    val utteranceId: String?,
    val startedAt: Long,
    val rate: Float,
)
