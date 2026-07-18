package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal interface TtsSpeechEngine {
    fun setLanguage(locale: Locale): Int

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
    private val chunkText: (String, Locale) -> List<TtsChunk> = { text, locale ->
        TtsChunker.chunk(text, locale)
    },
) {
    private var engine: TtsSpeechEngine? = null
    private val queue =
        TtsPlaybackQueue(
            stopEngine = { engine?.stop() },
            enqueue = { chunk, utteranceId ->
                engine?.speak(chunk.text, utteranceId) ?: TextToSpeech.ERROR
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
    ): Boolean {
        val activeEngine = engine ?: return false
        val chunks = chunkText(text, locale)
        if (chunks.isEmpty()) return false
        if (!acquireAudioFocus()) return false

        val languageStatus = activeEngine.setLanguage(locale)
        if (languageStatus < TextToSpeech.LANG_AVAILABLE) {
            queue.failBeforePlayback(TtsError.Synthesis, chunkCount = chunks.size)
            return false
        }
        queue.start(chunks)
        return state.value !is TtsState.Error
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
    fun skipNext() {
        if (!prepareToSkip()) return
        queue.skipNext()
    }

    @Synchronized
    fun skipPrevious() {
        if (!prepareToSkip()) return
        queue.skipPrevious()
    }

    private fun prepareToSkip(): Boolean =
        when (state.value) {
            is TtsState.Speaking -> true
            is TtsState.Paused -> acquireAudioFocus()
            is TtsState.Idle,
            is TtsState.Error,
            -> false
        }

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
}
