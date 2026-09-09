package dev.ipf.whitenoise.android.audio.tts

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal interface TtsSpeechEngine {
    val effectiveLocale: Locale? get() = null

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
 * timing. Queue preparation, history navigation, persistence, and pace tracking
 * live in smaller collaborators; the remaining methods intentionally share the
 * engine/session lock owned by this class.
 */
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
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

    private val preparation = TtsQueuePreparation(maxChunkLength)

    private var engine: TtsSpeechEngine? = null
    private var engineKey: String = ""
    private val rangeProbe = TtsRangeCapabilityProbe()
    private val pace = TtsPaceTracker(timingStore, { engineKey }, clock)
    private val utteranceRates = mutableMapOf<String, Float>()
    private var activeTiming: ActiveUtteranceTiming? = null

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
                pace.onQueueReplaced()
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

    private val preparationRequests =
        dev.ipf.whitenoise.android.state
            .StalenessGuard()

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
        pace.resetCalibration()
        utteranceRates.clear()
        activeTiming = null
        pace.resetMeasurements()
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
        pace.resetMeasurements()
    }

    /** Starts a queue only after engine, focus, and language gates succeed. */
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
        preparationRequests.advance()
        return speakPrepared(entries, locale, startSentenceIndex)
    }

    private fun speakPrepared(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int,
        preparedMessages: List<TtsQueuedMessage>? = null,
    ): Boolean {
        lastStartFailure = TtsStartFailure.None
        val activeEngine = engine
        val messages =
            if (activeEngine == null) {
                lastStartFailure = TtsStartFailure.EngineUnavailable
                null
            } else {
                prepareFocus(entries)?.let { focus ->
                    prepareStartMessages(activeEngine, entries, locale, preparedMessages, focus)?.let { it to focus }
                }
            } ?: return false
        return startPreparedQueue(messages.first, messages.second, locale, startSentenceIndex)
    }

    private fun startPreparedQueue(
        messages: List<TtsQueuedMessage>,
        focus: TtsStartFocus,
        locale: Locale,
        startSentenceIndex: Int,
    ): Boolean {
        if (capabilityLocale != locale) {
            rangeVerdictKey = restoreTtsRangeCapability(rangeProbe, timingStore, engineKey, locale)
        }
        capabilityLocale = locale
        activeFocusMode = focus.requestedMode
        queue.start(messages, startSentenceIndex = startSentenceIndex.coerceAtLeast(0))
        return state.value !is TtsState.Error
    }

    private fun prepareFocus(entries: List<TtsSpeakableEntry>): TtsStartFocus? {
        if (boundedSpeakableEntries(entries).isEmpty()) {
            lastStartFailure = TtsStartFailure.EmptyContent
            return null
        }
        val requestedFocusMode = requestedFocusMode()
        val previousFocusMode = activeFocusMode
        val hadSpeakingQueue = state.value is TtsState.Speaking
        return when {
            !acquireAudioFocus(requestedFocusMode) -> {
                lastStartFailure = TtsStartFailure.AudioFocusDenied
                restorePreviousFocusIfNeeded(hadSpeakingQueue, previousFocusMode)
                null
            }
            else -> TtsStartFocus(requestedFocusMode, previousFocusMode, hadSpeakingQueue)
        }
    }

    private fun prepareStartMessages(
        activeEngine: TtsSpeechEngine,
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        preparedMessages: List<TtsQueuedMessage>?,
        focus: TtsStartFocus,
    ): List<TtsQueuedMessage>? {
        val languageStatus = activeEngine.setLanguage(locale)
        val effectiveLocale = activeEngine.effectiveLocale ?: locale
        if (preparedMessages != null && effectiveLocale != locale) {
            audioFocus.release()
            lastStartFailure = TtsStartFailure.UnsupportedLanguage
            return null
        }
        val messages = preparedMessages ?: with(preparation) { entries.toQueuedMessages(effectiveLocale) }
        return validateStartMessages(messages, effectiveLocale, languageStatus, focus)
    }

    private fun validateStartMessages(
        messages: List<TtsQueuedMessage>,
        effectiveLocale: Locale,
        languageStatus: Int,
        focus: TtsStartFocus,
    ): List<TtsQueuedMessage>? {
        if (messages.isEmpty()) {
            lastStartFailure = TtsStartFailure.EmptyContent
            audioFocus.release()
            restorePreviousFocusIfNeeded(focus.wasSpeaking, focus.previousMode)
            return null
        }
        queueLocale = effectiveLocale
        return if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            lastStartFailure = TtsStartFailure.UnsupportedLanguage
            val chunkCount = messages.sumOf { it.chunks.size }
            queue.failBeforePlayback(
                TtsError.Synthesis,
                chunkCount = chunkCount,
                messageCount = messages.size,
                messagePreview = messages.first().preview,
            )
            null
        } else {
            messages
        }
    }

    /** Text work runs outside the controller lock; only its current owner can commit. */
    internal suspend fun speakAsync(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
        onPreparing: () -> Boolean,
    ): Boolean {
        val ticket = synchronized(this) { preparationTicket(entries, locale) } ?: return false
        try {
            return if (!onPreparing()) false else completePreparation(ticket, entries, startSentenceIndex)
        } finally {
            synchronized(this) {
                if (preparationRequests.isCurrent(ticket.first) && state.value is TtsState.Preparing) stop()
            }
        }
    }

    private suspend fun completePreparation(
        ticket: Triple<Long, TtsSpeechEngine, Locale>,
        entries: List<TtsSpeakableEntry>,
        startSentenceIndex: Int,
    ): Boolean {
        val messages =
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext()
                with(preparation) {
                    entries.toQueuedMessages(ticket.third) {
                        !job.isActive ||
                            !preparationRequests.isCurrent(ticket.first)
                    }
                }
            }
        return synchronized(this) {
            if (!preparationRequests.isCurrent(ticket.first) ||
                engine !== ticket.second ||
                (ticket.second.effectiveLocale ?: ticket.third) != ticket.third
            ) {
                return@synchronized false
            }
            speakPrepared(entries, ticket.third, startSentenceIndex, messages)
        }
    }

    private fun validatedEngine(entries: List<TtsSpeakableEntry>): TtsSpeechEngine? {
        val activeEngine = engine
        val failure =
            when {
                activeEngine == null -> TtsStartFailure.EngineUnavailable
                boundedSpeakableEntries(entries).isEmpty() -> TtsStartFailure.EmptyContent
                else -> null
            }
        if (failure != null) lastStartFailure = failure
        return if (failure == null) activeEngine else null
    }

    private fun preparationTicket(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
    ): Triple<Long, TtsSpeechEngine, Locale>? {
        return validatedEngine(entries)?.let { activeEngine ->
            val requestedMode = requestedFocusMode()
            val previousMode = activeFocusMode
            val wasSpeaking = state.value is TtsState.Speaking
            if (!prepareAsyncLanguage(activeEngine, locale, TtsStartFocus(requestedMode, previousMode, wasSpeaking))) {
                return null
            }
            val effective = activeEngine.effectiveLocale ?: locale
            val generation = preparationRequests.advance()
            activeFocusMode = requestedMode
            queueLocale = effective
            queue.beginTextPreparation()
            Triple(generation, activeEngine, effective)
        }
    }

    private fun prepareAsyncLanguage(
        activeEngine: TtsSpeechEngine,
        locale: Locale,
        focus: TtsStartFocus,
    ): Boolean {
        val failure =
            when {
                !acquireAudioFocus(focus.requestedMode) -> TtsStartFailure.AudioFocusDenied
                activeEngine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE -> {
                    audioFocus.release()
                    TtsStartFailure.UnsupportedLanguage
                }
                else -> null
            }
        if (failure != null) {
            lastStartFailure = failure
            restorePreviousFocusIfNeeded(focus.wasSpeaking, focus.previousMode)
        }
        return failure == null
    }

    /** Appends more messages to an active read-aloud session (auto-read). */
    @Synchronized
    fun appendSpeech(
        entry: TtsSpeakableEntry,
        // Retained for callers; appends must use the active session's resolved locale.
        @Suppress("UnusedParameter") locale: Locale,
    ): Boolean {
        val message = with(preparation) { entry.toQueuedMessage(queueLocale) }
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
        preparationRequests.advance()
        when (state.value) {
            is TtsState.Speaking, is TtsState.Preparing -> {
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
        expectedProjectionId: String? = null,
    ): TtsSeekResult {
        val queued = queue.queuedMessagesSnapshot().firstOrNull { it.messageIdHex == messageIdHex }
        if (queued != null &&
            expectedProjectionId != null &&
            queued.projectionId != expectedProjectionId
        ) {
            return TtsSeekResult.SentenceOutOfRange
        }
        return seekValidatedSentence(messageIdHex, sentenceIndex)
    }

    private fun seekValidatedSentence(
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
        val wasSpeaking = state.value is TtsState.Speaking || state.value is TtsState.Preparing
        queue.settleEdgeRequest(settlement)
        // A settle that parks the session has nothing left to speak, so focus
        // goes back exactly as it does for a user-driven pause.
        if (wasSpeaking && state.value is TtsState.Paused) audioFocus.release()
    }

    @Synchronized
    internal fun deferForTargetSeek(): Boolean = queue.deferForTargetSeek()

    /** Commit a freshly revalidated seek target without replacing the playback session. */
    @Synchronized
    internal fun installSeekTarget(
        entry: TtsSpeakableEntry,
        sentenceOrdinal: Int,
        sessionId: Long,
        projectionId: String,
    ): Boolean {
        if (state.value.sessionId != sessionId || !canNavigate() || entry.projectionId != projectionId) return false
        return with(preparation) { entry.toQueuedMessage(queueLocale) }
            ?.takeIf { target -> target.chunks.any { it.sentenceIndex == sentenceOrdinal } }
            ?.let { target -> installPreparedSeekTarget(entry, sentenceOrdinal, target) } ?: false
    }

    private fun installPreparedSeekTarget(
        entry: TtsSpeakableEntry,
        sentenceOrdinal: Int,
        target: TtsQueuedMessage,
    ): Boolean {
        val current = queue.queuedMessagesSnapshot()
        val direction =
            if (entry.timelineAt <
                (current.firstOrNull()?.timelineAt ?: 0uL)
            ) {
                TtsHistoryDirection.Older
            } else {
                TtsHistoryDirection.Newer
            }
        val merged = TtsHistoryWindow.merge(current, listOf(target), direction, entry.messageIdHex)
        val wasPaused = state.value is TtsState.Paused
        if (wasPaused && !acquireAudioFocus()) return false
        val installed = queue.replaceWindow(merged, entry.messageIdHex, TtsWindowSentenceTarget.First, sentenceOrdinal)
        if (installed && wasPaused) queue.resume()
        if (!installed && wasPaused) audioFocus.release()
        return installed
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
            if (canNavigate()) {
                with(
                    preparation,
                ) { entries.mapNotNull { it.toQueuedMessage(queueLocale) } }
            } else {
                emptyList()
            }
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

    // Navigation never acquires audio focus: while paused it only repositions
    // the queue, and speech starts again on resume().
    private fun canNavigate(): Boolean {
        val current = state.value
        return current is TtsState.Speaking || current is TtsState.Paused || current is TtsState.Preparing
    }

    /** Mixes only when peer media is active; otherwise read-aloud uses its ordinary focus contract. */
    private fun requestedFocusMode(): TtsAudioFocusMode =
        if (mediaMixEnabled() && isMediaPlaybackActive()) {
            TtsAudioFocusMode.MediaMix
        } else {
            TtsAudioFocusMode.Full
        }

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
        pace.onStart(chunk, appliedRate, startedAt)
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
                        msPerUnitAt1x = pace.msPerUnitAt1x,
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
            pace.onDone(chunk, timing, state.value.chunkCount)
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
        confirmTtsRangeCapability(rangeProbe, timingStore, rangeVerdictKey, wordTicker::stop)
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
        preparationRequests.advance()
        when (state.value) {
            is TtsState.Speaking, is TtsState.Preparing -> {
                queue.stop()
                audioFocus.release()
            }

            is TtsState.Paused,
            is TtsState.Error,
            -> queue.stop()

            is TtsState.Idle -> engine?.stop()
        }
    }

    val effectiveSpeechLocale: Locale? get() = if (canNavigate()) queueLocale else null

    /** The exact session-owned table used by the engine, shared with visible hit testing. */
    @Synchronized
    internal fun preparedSpeechFor(
        messageIdHex: String,
        projectionId: String?,
    ): PreparedSpeechMessage? =
        queue
            .queuedMessagesSnapshot()
            .firstOrNull {
                it.messageIdHex == messageIdHex &&
                    it.projectionId == projectionId
            }?.prepared
}

internal const val TTS_PREVIEW_MAX_LENGTH = 120

/**
 * Frame value stamped on synthetic range callbacks from the estimated word
 * schedule. Real engines report non-negative audio frame offsets.
 */
internal const val ESTIMATED_RANGE_FRAME = -1
