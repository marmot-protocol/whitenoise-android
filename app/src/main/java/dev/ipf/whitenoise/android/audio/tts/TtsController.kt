package dev.ipf.whitenoise.android.audio.tts

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal interface TtsSpeechEngine {
    fun setLanguage(locale: Locale): Int

    fun setSpeechRate(rate: Float)

    fun setCallbacks(
        onStart: (String?) -> Unit,
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
        onRangeStart: (String?, Int, Int, Int) -> Unit,
        onStop: (String?, Boolean) -> Unit,
    )

    fun clearCallbacks()

    fun speak(
        text: String,
        utteranceId: String,
    ): Int

    /** Submits an utterance with a bounded per-utterance volume override. */
    fun speak(
        text: String,
        utteranceId: String,
        volume: Float,
    ): Int = speak(text, utteranceId)

    fun stop()
}

internal interface TtsAudioFocus {
    fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean

    /** Requests focus appropriate for ordinary playback or explicit media mixing. */
    fun acquire(
        mode: TtsAudioFocusMode,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean = acquire(onFocusLoss, onOwnerSurrender)

    fun release()
}

/** Audio-focus policy for the current read-aloud session. */
internal enum class TtsAudioFocusMode {
    Full,
    MediaMix,
}

/** User-relevant reason the most recent start request did not begin. */
internal enum class TtsStartFailure {
    None,
    MediaNotActive,
    AudioFocusDenied,
    EngineUnavailable,
    UnsupportedLanguage,
    EmptyContent,
}

/**
 * Process-wide read-aloud controller. Engine lifecycle and trust selection stay
 * with the engine resolver; this class owns text chunking and playback state.
 *
 * Word-level position comes from two lanes sharing one delivery path. Engines
 * that report `onRangeStart` drive it directly; for engines that never do, an
 * estimated schedule replays synthetic range callbacks through the exact same
 * queue validation. The first real engine range wins permanently — the
 * estimate never paints another word once the engine has proven it reports
 * timing.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class TtsController internal constructor(
    private val audioFocus: TtsAudioFocus,
    private val maxChunkLength: Int = TextToSpeech.getMaxSpeechInputLength(),
    // Re-read per utterance so a rate change lands at the next sentence
    // boundary — quieter than re-queueing the current sentence.
    private val speechRate: () -> Float = { 1.0f },
    private val mediaMixEnabled: () -> Boolean = { false },
    private val mediaMixVolume: () -> Float = { 1.0f },
    private val isMediaPlaybackActive: () -> Boolean = { true },
    private val timingStore: TtsTimingStore? = null,
    private val wordTicker: TtsEstimatedWordTicker = TtsEstimatedWordTicker(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private companion object {
        const val MIN_SPEECH_VOLUME = 0f
        const val MAX_SPEECH_VOLUME = 1f
    }

    private var engine: TtsSpeechEngine? = null
    private var engineKey: String = ""
    private val rangeProbe = TtsRangeCapabilityProbe()
    private val paceCalibrator = TtsPaceCalibrator()
    private val utteranceRates = mutableMapOf<String, Float>()
    private var activeTiming: ActiveUtteranceTiming? = null

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
    private var activeFocusMode = TtsAudioFocusMode.Full

    internal var lastStartFailure: TtsStartFailure = TtsStartFailure.None
        private set

    // Locale of the active queue, retained so history pages loaded mid-session
    // chunk with the same sentence iterator the session started with.
    private var queueLocale: Locale = Locale.getDefault()

    // A usable range proves only the voice selected for this locale. Keep the
    // attachment-wide verdict provisional again when setLanguage can select a
    // different voice without replacing the engine instance.
    private var capabilityLocale: Locale? = null
    private var rangeVerdictKey: String = ""
    private val queue =
        TtsPlaybackQueue(
            stopEngine = {
                wordTicker.stop()
                utteranceRates.clear()
                activeTiming = null
                // Whatever is submitted next belongs to a new engine queue, so
                // no gap may span this point. The opener is deliberately LEFT
                // in place rather than discarded: refusing it by the epoch it
                // carries is what makes the refusal reason say what actually
                // happened, and a trace that reports every pause, skip and rate
                // change as "there was no opener" is worth less than one that
                // names the queue replacement. engineHasSpoken and
                // bootstrapRetired survive too: the voice does not go cold
                // again because a session ended, and re-colding it here would
                // refuse the only gap a two-sentence message produces.
                engineQueueLifetime.advance()
                engine?.stop()
            },
            enqueue = { chunk, utteranceId ->
                engine?.let {
                    val appliedRate = speechRate()
                    it.setSpeechRate(appliedRate)
                    utteranceRates[utteranceId] = appliedRate
                    val result =
                        if (activeFocusMode == TtsAudioFocusMode.MediaMix) {
                            it.speak(
                                chunk.text,
                                utteranceId,
                                mediaMixVolume().coerceIn(MIN_SPEECH_VOLUME, MAX_SPEECH_VOLUME),
                            )
                        } else {
                            it.speak(chunk.text, utteranceId)
                        }
                    if (result != TextToSpeech.SUCCESS) {
                        utteranceRates.remove(utteranceId)
                    }
                    result
                } ?: TextToSpeech.ERROR
            },
            onTerminal = ::releaseTerminalAudioFocus,
        )

    val state: StateFlow<TtsState> = queue.state

    @Synchronized
    internal fun attachEngine(
        engine: TtsSpeechEngine,
        engineKey: String = "",
    ) {
        if (this.engine === engine) return
        if (this.engine != null) {
            stopForEngineReplacement()
            this.engine?.clearCallbacks()
        }
        this.engine = engine
        // A different engine is a different capability and a different voice:
        // seed both from what this engine taught in earlier sessions, never
        // from whatever the previous engine left behind.
        this.engineKey = engineKey
        rangeProbe.restore(null)
        capabilityLocale = null
        rangeVerdictKey = ""
        paceCalibrator.reset(storedPace())
        utteranceRates.clear()
        activeTiming = null
        resetPaceMeasurement()
        engine.setCallbacks(::onStart, ::onDone, ::onError, ::onRangeStart, ::onStop)
    }

    @Synchronized
    internal fun detachEngine() {
        if (engine == null) return
        stopForEngineReplacement()
        engine?.clearCallbacks()
        engine = null
        engineKey = ""
        rangeProbe.restore(null)
        capabilityLocale = null
        rangeVerdictKey = ""
        utteranceRates.clear()
        activeTiming = null
        resetPaceMeasurement()
    }

    /** Starts a queue only after engine, media, focus, and language gates succeed. */
    @Synchronized
    fun speak(
        text: String,
        locale: Locale,
    ): Boolean = speak(listOf(TtsSpeakableEntry(senderKey = "", senderDisplayName = "", text = text)), locale)

    /** Starts projected messages without disturbing an old queue when a preflight gate refuses. */
    @Synchronized
    fun speak(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        lastStartFailure = TtsStartFailure.None
        val activeEngine =
            engine ?: run {
                lastStartFailure = TtsStartFailure.EngineUnavailable
                return false
            }
        val messages = entries.toQueuedMessages(locale)
        if (messages.isEmpty()) {
            lastStartFailure = TtsStartFailure.EmptyContent
            return false
        }
        val requestedFocusMode =
            if (mediaMixEnabled()) TtsAudioFocusMode.MediaMix else TtsAudioFocusMode.Full
        val previousFocusMode = activeFocusMode
        val hadSpeakingQueue = state.value is TtsState.Speaking
        if (requestedFocusMode == TtsAudioFocusMode.MediaMix && !isMediaPlaybackActive()) {
            lastStartFailure = TtsStartFailure.MediaNotActive
            return false
        }
        if (!acquireAudioFocus(requestedFocusMode)) {
            lastStartFailure = TtsStartFailure.AudioFocusDenied
            restorePreviousFocusIfNeeded(hadSpeakingQueue, previousFocusMode)
            return false
        }
        queueLocale = locale

        val languageStatus = activeEngine.setLanguage(locale)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            lastStartFailure = TtsStartFailure.UnsupportedLanguage
            val chunkCount = messages.sumOf { it.chunks.size }
            queue.failBeforePlayback(
                TtsError.Synthesis,
                chunkCount = chunkCount,
                messageCount = messages.size,
                messagePreview = messages.first().preview,
            )
            return false
        }
        if (capabilityLocale != locale) {
            rangeVerdictKey = ttsRangeVerdictKey(engineKey, locale)
            val scopedVerdict = timingStore?.rangeVerdict(rangeVerdictKey)
            // Older versions persisted one verdict per engine. Use it only as
            // provisional fallback evidence; the first conclusion in this
            // locale migrates it to the scoped key without deleting the legacy
            // value needed by locales that have not yet been observed.
            val legacyVerdict = if (scopedVerdict == null) timingStore?.rangeVerdict(engineKey) else null
            rangeProbe.restore(scopedVerdict ?: legacyVerdict)
        }
        capabilityLocale = locale
        activeFocusMode = requestedFocusMode
        queue.start(messages, startSentenceIndex = startSentenceIndex.coerceAtLeast(0))
        return state.value !is TtsState.Error
    }

    /** Appends more messages to an active read-aloud session (auto-read). */
    @Synchronized
    fun appendSpeech(
        entry: TtsSpeakableEntry,
        locale: Locale,
    ): Boolean {
        val message = entry.toQueuedMessage(locale)
        return message != null && queue.append(listOf(message))
    }

    /**
     * Called when the global speech rate changes so an in-flight queue picks
     * the new rate up at the next sentence boundary (the engine pre-buffers
     * remaining utterances, so without this the change would never land).
     */
    @Synchronized
    fun onSpeechRateChanged() {
        queue.refreshPendingChunksAtNextBoundary()
    }

    /** Re-submits pending utterances at the next boundary without rebuilding the session window. */
    @Synchronized
    fun onMediaMixVolumeChanged() {
        if (activeFocusMode == TtsAudioFocusMode.MediaMix) {
            queue.refreshPendingChunksAtNextBoundary()
        }
    }

    @Synchronized
    fun pause() {
        if (state.value !is TtsState.Speaking) return
        queue.pause()
        audioFocus.release()
    }

    @Synchronized
    fun resume() {
        if (state.value !is TtsState.Paused || !acquireAudioFocus()) return
        queue.resume()
    }

    @Synchronized
    fun stop() {
        when (state.value) {
            is TtsState.Speaking -> {
                queue.stop()
                audioFocus.release()
            }

            is TtsState.Paused,
            is TtsState.Error,
            -> queue.stop()

            is TtsState.Idle -> Unit
        }
    }

    @Synchronized
    fun skipNextMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!canNavigate()) return TtsNavigationOutcome.Inactive
        return queue.skipNextMessage(deferAtEdge)
    }

    @Synchronized
    fun skipPreviousMessage(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!canNavigate()) return TtsNavigationOutcome.Inactive
        return queue.skipPreviousMessage(deferAtEdge)
    }

    @Synchronized
    fun skipNextSentence(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!canNavigate()) return TtsNavigationOutcome.Inactive
        return queue.skipNextSentence(deferAtEdge)
    }

    @Synchronized
    fun skipPreviousSentence(deferAtEdge: Boolean = false): TtsNavigationOutcome {
        if (!canNavigate()) return TtsNavigationOutcome.Inactive
        return queue.skipPreviousSentence(deferAtEdge)
    }

    /** Seeks within the current queue. A tap-to-jump resumes paused playback. */
    @Synchronized
    fun seekToSentence(
        messageIdHex: String,
        sentenceIndex: Int,
    ): TtsSeekResult {
        val wasPaused = state.value is TtsState.Paused
        // A tap-to-jump is a playback intent. Do not silently move the paused
        // cursor when another audio owner refuses focus.
        if (wasPaused && !acquireAudioFocus()) return TtsSeekResult.SessionInactive
        val result = queue.seekTo(messageIdHex, sentenceIndex)
        if (
            wasPaused &&
            (result == TtsSeekResult.Repositioned || result == TtsSeekResult.RepositionedAcrossMessages)
        ) {
            queue.resume()
        } else if (wasPaused) {
            // Validation can race a window replacement between the rendered
            // hit and this synchronized call. Return focus if no seek landed.
            audioFocus.release()
        }
        return result
    }

    /**
     * Resolves a deferred edge navigation once its history request settled,
     * applying [settlement] to whatever cursor the queue is holding.
     */
    @Synchronized
    internal fun settleEdgeRequest(settlement: TtsEdgeSettlement) {
        val wasSpeaking = state.value is TtsState.Speaking
        queue.settleEdgeRequest(settlement)
        // A settle that parks the session has nothing left to speak, so focus
        // goes back exactly as it does for a user-driven pause.
        if (wasSpeaking && state.value is TtsState.Paused) audioFocus.release()
    }

    /** Message ids of the queued window in playback order, empty ids included. */
    @Synchronized
    internal fun queuedMessageIds(): List<String> = queue.queuedMessagesSnapshot().map(TtsQueuedMessage::messageIdHex)

    /** Queued window in playback order, for edge-walk anchoring by id + timeline position. */
    @Synchronized
    internal fun queuedMessagesSnapshot(): List<TtsQueuedMessage> = queue.queuedMessagesSnapshot()

    /**
     * Extends an active session's window with freshly projected history and
     * repositions onto [targetMessageIdHex]. Deliberately NOT routed through
     * [boundedSpeakableEntries]: the auto-read hazard cap must not silently
     * end a history session the user is steering — this window is bounded by
     * [TTS_HISTORY_WINDOW_MAX_MESSAGES] eviction instead.
     */
    @Synchronized
    internal fun extendReadAloudWindow(
        direction: TtsHistoryDirection,
        entries: List<TtsSpeakableEntry>,
        targetMessageIdHex: String,
        targetSentence: TtsWindowSentenceTarget,
    ): Boolean {
        val incoming =
            if (canNavigate()) entries.mapNotNull { it.toQueuedMessage(queueLocale) } else emptyList()
        // An empty extension has nothing to land on, so repositioning onto the
        // existing target would jump playback without adding any history.
        return if (incoming.isEmpty()) {
            false
        } else {
            val merged =
                TtsHistoryWindow.merge(
                    existing = queue.queuedMessagesSnapshot(),
                    incoming = incoming,
                    direction = direction,
                    targetMessageIdHex = targetMessageIdHex,
                )
            queue.replaceWindow(merged, targetMessageIdHex, targetSentence)
        }
    }

    /** Loads the calibrated pace for the active engine voice, falling back to the safe default. */
    private fun storedPace(): Double {
        val stored = timingStore?.msPerUnitAt1x(engineKey)
        return stored ?: TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X
    }

    /** Starts a new engine-queue lifetime and clears voice-specific pace evidence. */
    private fun resetPaceMeasurement() {
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
    private fun closePaceGapOpener(completedChunkIndex: Int) {
        val opener = gapOpener ?: return
        if (!engineQueueLifetime.isCurrent(opener.epoch) || opener.chunkIndex != completedChunkIndex) return
        gapOpener = opener.copy(completed = true, chunkCountAtCompletion = state.value.chunkCount)
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
        timingStore?.setMsPerUnitAt1x(engineKey, paceCalibrator.msPerUnitAt1x)
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

    // Navigation never acquires audio focus: while paused it only repositions
    // the queue, and speech starts again on resume().
    private fun canNavigate(): Boolean = state.value is TtsState.Speaking || state.value is TtsState.Paused

    /** Reacquires the session's latched focus policy across pause and seek. */
    private fun acquireAudioFocus(mode: TtsAudioFocusMode = activeFocusMode): Boolean =
        audioFocus.acquire(
            mode = mode,
            onFocusLoss = ::pause,
            // Permanent focus loss (another app took over playback, a voice
            // note started) pauses too: interruptions must not silently
            // discard the session's position (#1484). The retained session
            // stays resumable until explicit dismissal, natural completion, or
            // a security boundary ends it.
            onOwnerSurrender = ::pause,
        )

    /** Returns focus and clears the session-specific focus policy. */
    private fun releaseTerminalAudioFocus() {
        audioFocus.release()
        activeFocusMode = TtsAudioFocusMode.Full
    }

    /** Reclaims focus for a queue left intact by a refused replacement request. */
    private fun restorePreviousFocusIfNeeded(
        hadSpeakingQueue: Boolean,
        previousFocusMode: TtsAudioFocusMode,
    ) {
        if (hadSpeakingQueue && !acquireAudioFocus(previousFocusMode)) queue.pause()
    }

    /** Accepts a current utterance start and opens its range and pace-measurement window. */
    @Synchronized
    private fun onStart(utteranceId: String?) {
        // The queue's validation gate: a stale or superseded utterance neither
        // arms a schedule nor opens a calibration window.
        val activeUtteranceId = utteranceId ?: return
        val chunk = queue.submittedChunk(activeUtteranceId) ?: return
        rangeProbe.onUtteranceStart()
        val appliedRate = utteranceRates[activeUtteranceId] ?: speechRate()
        val startedAt = clock()
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
        activeTiming = ActiveUtteranceTiming(activeUtteranceId, startedAt, appliedRate)
        if (rangeProbe.reportsRanges != true) {
            // A stored capable verdict is provisional for evidence collection,
            // but it remains the playback-lane decision until enough answerable
            // silence overturns it. Starting the estimate over that lane races a
            // range-capable engine and can leave neither the engine nor estimate
            // owning the visible passage. A restored stale verdict still recovers:
            // onDone keeps examining it and arms the estimate after overturning it.
            wordTicker.start(
                utteranceId = activeUtteranceId,
                words =
                    TtsWordTimingEstimate.plan(
                        text = chunk.text,
                        locale = chunk.locale,
                        rate = appliedRate,
                        msPerUnitAt1x = paceCalibrator.msPerUnitAt1x,
                    ),
                emit = ::onEstimatedRange,
            )
        }
    }

    @Synchronized
    private fun onEstimatedRange(
        utteranceId: String,
        start: Int,
        end: Int,
    ): Boolean {
        // A capable verdict (restored or confirmed here) owns this playback lane;
        // estimated ranges are only accepted after silence overturns that verdict.
        if (rangeProbe.reportsRanges == true) return false
        return queue.onRangeStart(utteranceId, start, end, ESTIMATED_RANGE_FRAME) !=
            TtsPlaybackQueue.RangeApplication.Stale
    }

    @Synchronized
    private fun onDone(utteranceId: String?) {
        // Measure before the queue advances: submittedChunk validates that this
        // utterance is the one actually speaking, in the current generation.
        val chunk = queue.submittedChunk(utteranceId)
        if (chunk != null) {
            wordTicker.stop()
            val timing = activeTiming?.takeIf { it.utteranceId == utteranceId }
            if (timing != null) activeTiming = null
            utteranceId?.let(utteranceRates::remove)
            closePaceGapOpener(chunk.index)
            if (timing != null) observeBootstrapPace(chunk, timing)
            if (rangeProbe.onUtteranceDone(chunk.answerableLength())) {
                timingStore?.setRangeVerdict(rangeVerdictKey, false)
            }
        }
        queue.onDone(utteranceId)
    }

    @Synchronized
    private fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        // Only the active utterance may tear the schedule down: a stale
        // callback delivered after a requeue must not kill the ticker armed
        // for the utterance that replaced it. Queue-driven stop paths already
        // stop the ticker through stopEngine.
        if (queue.submittedChunk(utteranceId) != null) {
            wordTicker.stop()
            if (activeTiming?.utteranceId == utteranceId) activeTiming = null
            utteranceId?.let(utteranceRates::remove)
        }
        queue.onError(utteranceId, errorCode)
    }

    @Synchronized
    private fun onRangeStart(
        utteranceId: String?,
        start: Int,
        end: Int,
        frame: Int,
    ) {
        // Only an active callback that maps to a visible word proves range
        // capability. Stale, zero-width, partial, or unmappable callbacks must
        // not cancel the estimate or poison the persisted engine verdict.
        val application =
            queue.onRangeStart(
                utteranceId,
                start,
                end,
                frame,
                // While capability is unknown or known-silent, an unusable
                // engine callback must not erase a word already painted by the
                // estimate. Once the engine is confirmed capable, preserve the
                // original engine-only behavior and fall back to the sentence.
                retainVisibleWordOnFallback = rangeProbe.reportsRanges != true,
            )
        if (application != TtsPlaybackQueue.RangeApplication.VisibleWord) return
        // Confirm on EVERY usable range, not only the first: a verdict restored
        // from storage is provisional, and confirmation is what stops it being
        // obeyed for the life of the process after the engine has stopped
        // earning it. The snapshots are read before confirming, because
        // onRangeStart sets reportsRanges itself - guards evaluated afterwards
        // would always be false. A first proof retires the estimate and persists
        // a newly learned verdict. A legacy engine-only true verdict is written
        // once to this locale's key when the callback confirms it.
        val wasProven = rangeProbe.hasConfirmedRangeCapability
        rangeProbe.onRangeStart()
        if (!wasProven) {
            wordTicker.stop()
            if (timingStore?.rangeVerdict(rangeVerdictKey) != true) {
                timingStore?.setRangeVerdict(rangeVerdictKey, true)
            }
        }
    }

    @Synchronized
    private fun onStop(
        utteranceId: String?,
        interrupted: Boolean,
    ) {
        // Same staleness rule as onError: see there.
        if (queue.submittedChunk(utteranceId) != null) {
            wordTicker.stop()
            if (activeTiming?.utteranceId == utteranceId) activeTiming = null
            utteranceId?.let(utteranceRates::remove)
        }
        queue.onStopped(utteranceId, interrupted)
    }

    private fun stopForEngineReplacement() {
        when (state.value) {
            is TtsState.Speaking -> {
                queue.stop()
                audioFocus.release()
            }

            is TtsState.Paused,
            is TtsState.Error,
            -> queue.stop()

            is TtsState.Idle -> engine?.stop()
        }
    }

    private fun List<TtsSpeakableEntry>.toQueuedMessages(locale: Locale): List<TtsQueuedMessage> =
        boundedSpeakableEntries(this).mapNotNull { it.toQueuedMessage(locale) }

    private fun TtsSpeakableEntry.toQueuedMessage(locale: Locale): TtsQueuedMessage? {
        val trimStart = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val trimEnd = text.indexOfLast { !it.isWhitespace() } + 1
        val trimmed = text.substring(trimStart, trimEnd)
        val announcementName = senderDisplayName.trim()
        val sentenceChunks =
            TtsChunker.chunk(
                text = trimmed,
                locale = locale,
                maxChunkLength = maxChunkLength,
                leadingChunkReserve = senderAnnouncementReserve(announcementName),
            )
        return sentenceChunks.takeIf { it.isNotEmpty() }?.let { chunks ->
            TtsQueuedMessage(
                senderKey = senderKey,
                senderDisplayName = announcementName,
                preview = trimmed.take(TTS_PREVIEW_MAX_LENGTH),
                // The queue reflattens indices itself — sentence identity must survive.
                chunks =
                    chunks.map { chunk ->
                        val sourceStart = trimStart + chunk.sourceStart
                        val sourceEnd = trimStart + chunk.sourceEnd
                        chunk.copy(
                            index = 0,
                            messageIdHex = messageIdHex,
                            projectionId = projectionId,
                            timelineAt = timelineAt,
                            visibleSpans = spokenTextSpans.forChunk(sourceStart, sourceEnd),
                        )
                    },
                messageIdHex = messageIdHex,
                projectionId = projectionId,
                timelineAt = timelineAt,
            )
        }
    }

    private fun List<TtsSpokenTextSpan>.forChunk(
        sourceStart: Int,
        sourceEnd: Int,
    ): List<TtsSpokenTextSpan> =
        mapNotNull { span ->
            val start = maxOf(sourceStart, span.spoken.start)
            val end = minOf(sourceEnd, span.spoken.end)
            if (start >= end) {
                null
            } else {
                val visibleStart = span.visible.start + (start - span.spoken.start)
                TtsSpokenTextSpan(
                    spoken = TtsTextRange(start - sourceStart, end - sourceStart),
                    visible =
                        TtsVisibleTextSpan(
                            leafId = span.visible.leafId,
                            start = visibleStart,
                            end = visibleStart + (end - start),
                        ),
                )
            }
        }

    private fun senderAnnouncementReserve(displayName: String): Int =
        displayName
            .takeIf(String::isNotEmpty)
            ?.let { "$it: ".length } ?: 0
}

private data class ActiveUtteranceTiming(
    val utteranceId: String?,
    val startedAt: Long,
    val rate: Float,
)

internal const val TTS_PREVIEW_MAX_LENGTH = 120

/**
 * Frame value stamped on synthetic range callbacks from the estimated word
 * schedule. Real engines report non-negative audio frame offsets.
 */
internal const val ESTIMATED_RANGE_FRAME = -1
