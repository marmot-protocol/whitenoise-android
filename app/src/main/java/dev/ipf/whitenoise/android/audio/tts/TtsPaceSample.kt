package dev.ipf.whitenoise.android.audio.tts

/** One utterance's audible length, measured from the gap between two utterance starts. */
internal data class TtsPaceSample(
    val units: Int,
    val elapsedMs: Long,
    val rate: Float,
)

/** Why a gap measured nothing. The single most useful field in a trace of this lane. */
internal enum class TtsPaceRefusal {
    NO_OPENER,
    OPENER_NEVER_COMPLETED,
    ENGINE_QUEUE_REPLACED,
    NOT_THE_NEXT_CHUNK,
    OPENER_WAS_FIRST_SPOKEN,
    SUCCESSOR_QUEUED_AFTER_OPENER_DONE,
    SEAM_WOULD_DOMINATE,
}

/** Either the sample a gap yielded, or the reason it yielded none. */
internal sealed interface TtsPaceOutcome {
    data class Measured(
        val sample: TtsPaceSample,
    ) : TtsPaceOutcome

    data class Refused(
        val reason: TtsPaceRefusal,
    ) : TtsPaceOutcome
}

/** What the controller knows about the utterance that opened a gap. */
internal data class TtsPaceGapOpener(
    /** Which engine queue it was submitted to; see [ttsPaceOutcomeOf]. */
    val epoch: Long,
    val chunkIndex: Int,
    val startedAtMs: Long,
    val rate: Float,
    /** Weighted length of the exact payload handed to the engine. */
    val units: Int,
    /** Whether the payload ends a sentence, which decides the seam. */
    val endsSentence: Boolean,
    /** Whether this was the first thing the engine spoke since it was attached. */
    val wasFirstSpokenByEngine: Boolean,
    /** Set when the engine reported this utterance done. */
    val completed: Boolean = false,
    /** Chunks the queue held at that moment; see [TtsPaceRefusal.SUCCESSOR_QUEUED_AFTER_OPENER_DONE]. */
    val chunkCountAtCompletion: Int = 0,
)

/**
 * How fast the voice actually speaks, measured between two utterance starts.
 *
 * The estimated word marker spends a learned rate open loop, so a systematic
 * error in that rate is a marker that drifts further from the voice the longer
 * a sentence runs. The obvious measurement — the interval from `onStart` to
 * `onDone` of one utterance — is not one. Writing `L` for the interval between
 * `onStart` and audible speech, that interval is `L + audible`, and nothing in
 * this process knows `L`: an engine using the framework's playback path reports
 * `onStart` when its audio item begins, so `L` is near zero, while an engine
 * reporting at synthesis start has an `L` that is larger and, by
 * [TtsEstimatedWordTicker]'s own account, longest while the voice warms up.
 * Deducting a constant for it biases every sample from a given engine the same
 * way, and a rate learned too fast puts the marker AHEAD of the voice, which is
 * the worse of the two failures — a marker that has already moved on cannot be
 * read as nearly there.
 *
 * The gap between two consecutive `onStart` callbacks does not contain `L`:
 *
 *     start(k+1) − start(k) = audible(k) + seam + (L(k) − L(k+1))
 *
 * and `L` cancels between two utterances of the same warmed engine. It does not
 * cancel when the opener is the first thing that engine spoke, which is exactly
 * the warm-up case, so that gap is refused rather than assumed away.
 *
 * A gap only measures the opener when the opener really played whole and the
 * utterance now starting is the one that was queued behind it. Everything below
 * is a way for that to be false. Each has a named reason, because the useful
 * question when this lane goes quiet is never "did it measure" but "which rule
 * refused, and could it ever have been satisfied".
 *
 * **[ENGINE_QUEUE_REPLACED]** covers most of them at once. `TtsPlaybackQueue`
 * stops and re-enqueues the engine on every disruptive path — starting,
 * pausing, stopping, failing, and every requeue, which is also how a speech
 * rate change lands. The controller stamps each opener with the epoch of the
 * engine queue it was submitted to, and that one equality rejects a gap
 * straddling a pause, a stop, a skip, a rate change or a window replacement.
 * (The queue's own generation counter advances on exactly those paths plus
 * `finishPlayback`, which ends the session, so no `onStart` follows it before a
 * fresh start bumps the epoch too.)
 *
 * **[OPENER_NEVER_COMPLETED]** — a cut-short utterance's audio was discarded
 * while its weights still describe all of it, so the rate comes out
 * proportionally too fast.
 *
 * **[SUCCESSOR_QUEUED_AFTER_OPENER_DONE]** is the one contaminated case the
 * epoch cannot see. `TtsPlaybackQueue.append` adds an utterance without
 * replacing the queue, and that is normally exactly right: an auto-read message
 * arriving mid-playback really does follow the opener through the engine. But
 * when a deferred history request is in flight, the last chunk's completion
 * parks instead of ending the session, and an append arriving later lands on
 * `openerIndex + 1` after an unbounded wait. The two are separated by whether
 * the successor already existed when the opener completed.
 *
 * **[SEAM_WOULD_DOMINATE]** is the error budget. What remains in the gap after
 * the opener's audio is the seam: the engine's handover, plus the breath it
 * takes after sentence-final punctuation. That breath must be subtracted
 * because [TtsWordTimingEstimate.pauseWeightOf] deliberately does not charge
 * for it — the queue splits utterances at sentence boundaries precisely so that
 * pause falls between them — but it must NOT be subtracted when the chunker
 * split inside one long sentence, where the opener ends mid-clause and no such
 * breath exists. Modelling the seam wrong is the residual error of this whole
 * approach, and it is bounded by refusing gaps in which the seam would be a
 * large share: requiring the raw gap to be at least [MIN_SEAM_SIGNIFICANCE]
 * times the seam holds the sample's error under a quarter even for a seam that
 * is wrong by 100%, and [TtsPaceCalibrator] then blends in only a quarter of
 * each sample. The bias it replaces is ~160 ms of every utterance, with no way
 * to refuse it and no convergence.
 *
 * That floor costs something at speed, and it is worth naming: the handover
 * does not shrink with the playback rate while the speech does, so a listener
 * at 3x measures only from longer sentences. That is the right answer rather
 * than a gap to close — through a constant handover, a very short gap genuinely
 * cannot say how fast a voice is — and the bootstrap in the controller is what
 * keeps such a listener from having no rate at all.
 *
 * Fail-closed is the right bias, but only if the measurement still ARRIVES,
 * and it is worth being exact about when it first does. Every condition but the
 * cold-voice one is satisfied by construction on a straight read: the queue
 * submits the whole remainder up front, so each successor is queued behind its
 * opener, nothing is replaced, and nothing was cut short. The cold-voice rule
 * costs one utterance per ATTACHMENT, not per session - so the first
 * measurement lands on the THIRD utterance the engine speaks after it was
 * attached. In a three-sentence message that is the same message; in a chat of
 * one- and two-sentence messages it is the second or third message, because
 * the engine stays attached across them. A reader who opens the app, plays one
 * two-sentence message and stops has no gap sample, and keeps the in-process
 * bootstrap - which is what the bootstrap is for.
 */
internal fun ttsPaceOutcomeOf(
    opener: TtsPaceGapOpener?,
    startingEpoch: Long,
    startingChunkIndex: Int,
    startingAtMs: Long,
): TtsPaceOutcome {
    val refusal = opener.refusalFor(startingEpoch, startingChunkIndex, startingAtMs)
    if (refusal != null) return TtsPaceOutcome.Refused(refusal)
    val measured = requireNotNull(opener)
    return TtsPaceOutcome.Measured(
        TtsPaceSample(
            units = measured.units,
            elapsedMs = startingAtMs - measured.startedAtMs - seamMsFor(measured.endsSentence, measured.rate),
            rate = measured.rate,
        ),
    )
}

@Suppress("ReturnCount")
private fun TtsPaceGapOpener?.refusalFor(
    startingEpoch: Long,
    startingChunkIndex: Int,
    startingAtMs: Long,
): TtsPaceRefusal? {
    if (this == null || units <= 0) return TtsPaceRefusal.NO_OPENER
    if (!completed) return TtsPaceRefusal.OPENER_NEVER_COMPLETED
    if (startingEpoch != epoch) return TtsPaceRefusal.ENGINE_QUEUE_REPLACED
    if (startingChunkIndex != chunkIndex + 1) return TtsPaceRefusal.NOT_THE_NEXT_CHUNK
    if (wasFirstSpokenByEngine) return TtsPaceRefusal.OPENER_WAS_FIRST_SPOKEN
    if (chunkCountAtCompletion <= chunkIndex + 1) return TtsPaceRefusal.SUCCESSOR_QUEUED_AFTER_OPENER_DONE
    val rawGapMs = startingAtMs - startedAtMs
    if (rawGapMs < seamMsFor(endsSentence, rate) * MIN_SEAM_SIGNIFICANCE) {
        return TtsPaceRefusal.SEAM_WOULD_DOMINATE
    }
    return null
}

/**
 * The silence between two utterances, at [rate].
 *
 * The breath is only there when the opener actually ended a sentence: a chunk
 * split inside one long sentence ends mid-clause and is followed by no breath
 * at all. And it is audio, so it shortens with the speaking rate exactly as the
 * speech around it does. The handover is the engine's own machinery between two
 * utterances and does not, which is why the two are separate constants rather
 * than one number: subtracting a fixed 220 ms at 3x removes more than the
 * silence that was actually there, and over-subtracting teaches a rate that is
 * too fast.
 */
private fun seamMsFor(
    endsSentence: Boolean,
    rate: Float,
): Long {
    if (!endsSentence) return TTS_UTTERANCE_HANDOVER_MS
    val breathMs = TTS_SENTENCE_BREATH_MS / TtsWordTimingEstimate.clampRate(rate)
    return TTS_UTTERANCE_HANDOVER_MS + breathMs.toLong()
}

/**
 * Whether [text] ends a sentence, ignoring the closing punctuation that can
 * trail the character actually carrying the pause.
 */
internal fun ttsUtteranceEndsSentence(text: String): Boolean {
    val bare = text.trimEnd { it.isWhitespace() || it in SENTENCE_TRAILING_WRAPPERS }
    val last = bare.lastOrNull() ?: return false
    return last in SENTENCE_TERMINALS
}

/**
 * The engine's own gap between finishing one utterance and starting the next.
 * A seed, not a measurement: it comes from a reader that submits one utterance
 * at a time, while this queue pre-buffers the whole window, and a pre-buffered
 * handover should be the smaller of the two.
 */
internal const val TTS_UTTERANCE_HANDOVER_MS = 120L

/** The breath an engine takes after sentence-final punctuation. */
internal const val TTS_SENTENCE_BREATH_MS = 100L

/** How much larger than the seam a gap must be before the seam's error is tolerable. */
internal const val MIN_SEAM_SIGNIFICANCE = 4

private const val SENTENCE_TERMINALS = ".?!…"
private const val SENTENCE_TRAILING_WRAPPERS = ")]}\"'”’"
