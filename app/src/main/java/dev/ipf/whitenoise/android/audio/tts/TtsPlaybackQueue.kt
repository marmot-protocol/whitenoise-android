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
    private var refreshAtNextBoundary = false

    /**
     * Applies changed enqueue-time parameters (speech rate) at the next chunk
     * boundary. The engine pre-buffers every remaining utterance at enqueue
     * time, so without a re-queue a mid-playback change would never land;
     * re-queueing only at the boundary keeps the current sentence unbroken.
     */
    fun refreshPendingChunksAtNextBoundary() {
        if (_state.value is TtsState.Speaking) refreshAtNextBoundary = true
    }

    fun start(chunks: List<TtsChunk>) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
        this.chunks = chunks
        currentIndex = 0
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        enqueueFromCurrentIndex()
    }

    /**
     * Extends an active queue with more sentences (auto-read live
     * continuation). No-op when idle or errored — appending must never
     * resurrect a finished session. While speaking, the new chunks enqueue
     * immediately behind the engine's pending utterances; while paused,
     * resume() re-enqueues everything from the current index anyway.
     */
    fun append(moreChunks: List<TtsChunk>): Boolean {
        if (moreChunks.isEmpty()) return false
        val current = _state.value
        if (current !is TtsState.Speaking && current !is TtsState.Paused) return false
        val base = chunks.size
        val reindexed = moreChunks.mapIndexed { offset, chunk -> chunk.copy(index = base + offset) }
        chunks = chunks + reindexed
        if (current is TtsState.Speaking) {
            _state.value = TtsState.Speaking(currentIndex, chunks.size)
            for (chunk in reindexed) {
                val utteranceId = utteranceId(generation, chunk.index)
                val result = enqueue(chunk, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    onError(utteranceId, result)
                    break
                }
            }
        } else {
            _state.value = TtsState.Paused(currentIndex, chunks.size)
        }
        return true
    }

    fun failBeforePlayback(
        error: TtsError,
        chunkCount: Int,
    ) {
        fail(error = error, chunkIndex = 0, chunkCount = chunkCount)
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
            if (refreshAtNextBoundary) {
                refreshAtNextBoundary = false
                requeueFrom(next)
            }
        }
    }

    fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        val failedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || failedIndex < currentIndex) return
        val error =
            when (errorCode) {
                TextToSpeech.ERROR_NETWORK,
                TextToSpeech.ERROR_NETWORK_TIMEOUT,
                -> TtsError.Network

                else -> TtsError.Synthesis
            }
        fail(error = error, chunkIndex = failedIndex, chunkCount = chunks.size)
    }

    private fun fail(
        error: TtsError,
        chunkIndex: Int,
        chunkCount: Int,
    ) {
        stopEngine()
        generation += 1
        chunks = emptyList()
        currentIndex = chunkIndex
        _state.value = TtsState.Error(error, chunkIndex, chunkCount)
        onTerminal()
    }

    private fun requeueFrom(index: Int) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
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
