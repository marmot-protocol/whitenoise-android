package dev.ipf.whitenoise.android.audio.tts

import android.os.SystemClock
import android.speech.tts.TextToSpeech
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

    fun stop()
}

internal interface TtsAudioFocus {
    fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean

    fun release()
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
    private val timingStore: TtsTimingStore? = null,
    private val wordTicker: TtsEstimatedWordTicker = TtsEstimatedWordTicker(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var engine: TtsSpeechEngine? = null
    private var engineKey: String = ""
    private val rangeProbe = TtsRangeCapabilityProbe()
    private val paceCalibrator = TtsPaceCalibrator()
    private val utteranceRates = mutableMapOf<String, Float>()
    private var activeTiming: ActiveUtteranceTiming? = null

    // Locale of the active queue, retained so history pages loaded mid-session
    // chunk with the same sentence iterator the session started with.
    private var queueLocale: Locale = Locale.getDefault()
    private val queue =
        TtsPlaybackQueue(
            stopEngine = {
                wordTicker.stop()
                utteranceRates.clear()
                activeTiming = null
                engine?.stop()
            },
            enqueue = { chunk, utteranceId ->
                engine?.let {
                    val appliedRate = speechRate()
                    it.setSpeechRate(appliedRate)
                    utteranceRates[utteranceId] = appliedRate
                    val result = it.speak(chunk.text, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        utteranceRates.remove(utteranceId)
                    }
                    result
                } ?: TextToSpeech.ERROR
            },
            onTerminal = audioFocus::release,
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
        rangeProbe.restore(timingStore?.rangeVerdict(engineKey))
        paceCalibrator.reset(
            timingStore?.msPerUnitAt1x(engineKey) ?: TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X,
        )
        utteranceRates.clear()
        activeTiming = null
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
        utteranceRates.clear()
        activeTiming = null
    }

    @Synchronized
    fun speak(
        text: String,
        locale: Locale,
    ): Boolean = speak(listOf(TtsSpeakableEntry(senderKey = "", senderDisplayName = "", text = text)), locale)

    @Synchronized
    fun speak(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val activeEngine = engine ?: return false
        val messages = entries.toQueuedMessages(locale)
        if (messages.isEmpty()) return false
        if (!acquireAudioFocus()) return false
        queueLocale = locale

        val languageStatus = activeEngine.setLanguage(locale)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            val chunkCount = messages.sumOf { it.chunks.size }
            queue.failBeforePlayback(
                TtsError.Synthesis,
                chunkCount = chunkCount,
                messageCount = messages.size,
                messagePreview = messages.first().preview,
            )
            return false
        }
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

    // Navigation never acquires audio focus: while paused it only repositions
    // the queue, and speech starts again on resume().
    private fun canNavigate(): Boolean = state.value is TtsState.Speaking || state.value is TtsState.Paused

    private fun acquireAudioFocus(): Boolean =
        audioFocus.acquire(
            onFocusLoss = ::pause,
            // Permanent focus loss (another app took over playback, a voice
            // note started) pauses too: interruptions must not silently
            // discard the session's position (#1484). The retained session
            // stays resumable until explicit dismissal, natural completion, or
            // a security boundary ends it.
            onOwnerSurrender = ::pause,
        )

    @Synchronized
    private fun onStart(utteranceId: String?) {
        // The queue's validation gate: a stale or superseded utterance neither
        // arms a schedule nor opens a calibration window.
        val activeUtteranceId = utteranceId ?: return
        val chunk = queue.submittedChunk(activeUtteranceId) ?: return
        rangeProbe.onUtteranceStart()
        val appliedRate = utteranceRates[activeUtteranceId] ?: speechRate()
        activeTiming = ActiveUtteranceTiming(activeUtteranceId, clock(), appliedRate)
        if (rangeProbe.reportsRanges != true) {
            // Runs whenever the engine has not PROVEN it reports timing. Waiting
            // for the probe to prove the opposite would leave the first message or
            // two of every fresh session with no word highlight at all; running
            // optimistically is safe because the first real range callback yields
            // this schedule permanently.
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
        // A real engine range that arrived mid-utterance takes over permanently.
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
            if (timing != null) {
                // onStart precedes audible speech by the same bounded lead-in
                // used by the estimate. Android guarantees onDone follows full
                // playback, so learn that audible interval without inventing a
                // second, engine-specific teardown deduction.
                val elapsedSinceStart = clock() - timing.startedAt
                if (elapsedSinceStart > TTS_ESTIMATED_AUDIO_LEAD_IN_MS) {
                    val moved =
                        paceCalibrator.observe(
                            unitCount = TtsWordTimingEstimate.weightedLengthOf(chunk.text),
                            elapsedMs = elapsedSinceStart - TTS_ESTIMATED_AUDIO_LEAD_IN_MS,
                            rate = timing.rate,
                        )
                    if (moved) timingStore?.setMsPerUnitAt1x(engineKey, paceCalibrator.msPerUnitAt1x)
                }
            }
            if (rangeProbe.onUtteranceDone(chunk.text.length)) {
                timingStore?.setRangeVerdict(engineKey, false)
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
        if (
            application == TtsPlaybackQueue.RangeApplication.VisibleWord &&
            rangeProbe.reportsRanges != true
        ) {
            rangeProbe.onRangeStart()
            wordTicker.stop()
            timingStore?.setRangeVerdict(engineKey, true)
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
