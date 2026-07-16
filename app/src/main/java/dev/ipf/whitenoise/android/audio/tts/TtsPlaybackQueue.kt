package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TtsState {
    val chunkIndex: Int
    val chunkCount: Int

    data class Idle(
        override val chunkIndex: Int = 0,
        override val chunkCount: Int = 0,
    ) : TtsState

    data class Speaking(
        override val chunkIndex: Int,
        override val chunkCount: Int,
    ) : TtsState

    data class Paused(
        override val chunkIndex: Int,
        override val chunkCount: Int,
    ) : TtsState

    data class Error(
        val error: TtsError,
        override val chunkIndex: Int,
        override val chunkCount: Int,
    ) : TtsState
}

enum class TtsError {
    Network,
    Synthesis,
}

/**
 * Pure sentence-queue state machine. The Android TTS owner supplies the two
 * engine operations so queue/progress behavior remains deterministic in tests.
 */
internal class TtsPlaybackQueue(
    private val stopEngine: () -> Unit,
    private val enqueue: (chunk: TtsChunk, utteranceId: String) -> Int,
    private val onTerminal: () -> Unit = {},
) {
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var chunks: List<TtsChunk> = emptyList()
    private var currentIndex = 0
    private var generation = 0L

    fun start(chunks: List<TtsChunk>) {
        stopEngine()
        generation += 1
        this.chunks = chunks
        currentIndex = 0
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        enqueueFromCurrentIndex()
    }

    fun pause() {
        val speaking = _state.value as? TtsState.Speaking ?: return
        stopEngine()
        generation += 1
        currentIndex = speaking.chunkIndex
        _state.value = TtsState.Paused(currentIndex, chunks.size)
    }

    fun resume() {
        val paused = _state.value as? TtsState.Paused ?: return
        currentIndex = paused.chunkIndex
        enqueueFromCurrentIndex()
    }

    fun stop() {
        stopEngine()
        generation += 1
        chunks = emptyList()
        currentIndex = 0
        _state.value = TtsState.Idle()
    }

    fun skipNext() {
        if (_state.value !is TtsState.Speaking && _state.value !is TtsState.Paused) return
        val next = currentIndex + 1
        if (next >= chunks.size) {
            stopEngine()
            generation += 1
            val completedCount = chunks.size
            chunks = emptyList()
            currentIndex = completedCount
            _state.value = TtsState.Idle(completedCount, completedCount)
            onTerminal()
            return
        }
        requeueFrom(next)
    }

    fun skipPrevious() {
        if (_state.value !is TtsState.Speaking && _state.value !is TtsState.Paused) return
        requeueFrom((currentIndex - 1).coerceAtLeast(0))
    }

    fun onDone(utteranceId: String?) {
        val completedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || completedIndex != currentIndex) return
        val next = completedIndex + 1
        if (next >= chunks.size) {
            val completedCount = chunks.size
            chunks = emptyList()
            currentIndex = completedCount
            _state.value = TtsState.Idle(completedCount, completedCount)
            onTerminal()
        } else {
            currentIndex = next
            _state.value = TtsState.Speaking(currentIndex, chunks.size)
        }
    }

    fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        val failedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || failedIndex < currentIndex) return
        val chunkCount = chunks.size
        stopEngine()
        generation += 1
        chunks = emptyList()
        currentIndex = failedIndex
        _state.value =
            TtsState.Error(
                error = if (errorCode == TextToSpeech.ERROR_NETWORK) TtsError.Network else TtsError.Synthesis,
                chunkIndex = failedIndex,
                chunkCount = chunkCount,
            )
        onTerminal()
    }

    private fun requeueFrom(index: Int) {
        stopEngine()
        generation += 1
        currentIndex = index
        enqueueFromCurrentIndex()
    }

    private fun enqueueFromCurrentIndex() {
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        _state.value = TtsState.Speaking(currentIndex, chunks.size)
        for (chunk in chunks.drop(currentIndex)) {
            val utteranceId = utteranceId(generation, chunk.index)
            val result = enqueue(chunk, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onError(utteranceId, result)
                break
            }
        }
    }

    private fun parseCurrentGenerationIndex(utteranceId: String?): Int? {
        val match = UTTERANCE_ID_PATTERN.matchEntire(utteranceId ?: return null) ?: return null
        val callbackGeneration = match.groupValues[1].toLongOrNull() ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        return index.takeIf { callbackGeneration == generation && it in chunks.indices }
    }

    private companion object {
        val UTTERANCE_ID_PATTERN = Regex("whitenoise\\.tts\\.(\\d+)\\.(\\d+)")

        fun utteranceId(
            generation: Long,
            index: Int,
        ): String = "whitenoise.tts.$generation.$index"
    }
}
