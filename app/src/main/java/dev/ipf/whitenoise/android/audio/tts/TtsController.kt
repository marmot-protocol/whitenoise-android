package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal interface TtsSpeechEngine {
    fun setLanguage(locale: Locale): Int

    fun setSpeechRate(rate: Float)

    fun setCallbacks(
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
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
 */
class TtsController internal constructor(
    private val audioFocus: TtsAudioFocus,
    private val maxChunkLength: Int = TextToSpeech.getMaxSpeechInputLength(),
    // Re-read per utterance so a rate change lands at the next sentence
    // boundary — quieter than re-queueing the current sentence.
    private val speechRate: () -> Float = { 1.0f },
) {
    private var engine: TtsSpeechEngine? = null

    // Locale of the active queue, retained so history pages loaded mid-session
    // chunk with the same sentence iterator the session started with.
    private var queueLocale: Locale = Locale.getDefault()
    private val queue =
        TtsPlaybackQueue(
            stopEngine = { engine?.stop() },
            enqueue = { chunk, utteranceId ->
                engine?.let {
                    it.setSpeechRate(speechRate())
                    it.speak(chunk.text, utteranceId)
                } ?: TextToSpeech.ERROR
            },
            onTerminal = audioFocus::release,
        )

    val state: StateFlow<TtsState> = queue.state

    @Synchronized
    internal fun attachEngine(engine: TtsSpeechEngine) {
        if (this.engine === engine) return
        if (this.engine != null) {
            stopForEngineReplacement()
            this.engine?.clearCallbacks()
        }
        this.engine = engine
        engine.setCallbacks(::onDone, ::onError)
    }

    @Synchronized
    internal fun detachEngine() {
        if (engine == null) return
        stopForEngineReplacement()
        engine?.clearCallbacks()
        engine = null
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
        queue.start(messages)
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
     * Resolves a deferred edge navigation once its history request settled. A
     * terminal chunk that parked while the page was in flight completes now,
     * unless [retainCursor] keeps the window for a retry.
     */
    @Synchronized
    internal fun settleEdgeRequest(retainCursor: Boolean) {
        queue.settleEdgeRequest(retainCursor)
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
            onOwnerSurrender = ::surrender,
        )

    @Synchronized
    private fun surrender() {
        if (state.value is TtsState.Speaking || state.value is TtsState.Paused) {
            queue.stop()
        }
    }

    @Synchronized
    private fun onDone(utteranceId: String?) {
        queue.onDone(utteranceId)
    }

    @Synchronized
    private fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        queue.onError(utteranceId, errorCode)
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
        val trimmed = text.trim()
        val announcementName = senderDisplayName.trim()
        val sentenceChunks =
            trimmed
                .takeIf(String::isNotEmpty)
                ?.let { messageText ->
                    TtsChunker.chunk(
                        text = messageText,
                        locale = locale,
                        maxChunkLength = maxChunkLength,
                        leadingChunkReserve = senderAnnouncementReserve(announcementName),
                    )
                }.orEmpty()
        return sentenceChunks.takeIf { it.isNotEmpty() }?.let { chunks ->
            TtsQueuedMessage(
                senderKey = senderKey,
                senderDisplayName = announcementName,
                preview = trimmed.take(TTS_PREVIEW_MAX_LENGTH),
                // The queue reflattens indices itself — sentence identity must survive.
                chunks = chunks.map { chunk -> chunk.copy(index = 0) },
                messageIdHex = messageIdHex,
                timelineAt = timelineAt,
            )
        }
    }

    private fun senderAnnouncementReserve(displayName: String): Int =
        displayName
            .takeIf(String::isNotEmpty)
            ?.let { "$it: ".length } ?: 0
}

internal const val TTS_PREVIEW_MAX_LENGTH = 120
