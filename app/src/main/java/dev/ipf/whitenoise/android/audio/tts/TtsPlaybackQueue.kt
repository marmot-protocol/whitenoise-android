package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TtsState {
    val chunkIndex: Int
    val chunkCount: Int
    val messageIndex: Int
    val messageCount: Int
    val messagePreview: String

    data class Idle(
        override val chunkIndex: Int = 0,
        override val chunkCount: Int = 0,
        override val messageIndex: Int = 0,
        override val messageCount: Int = 0,
        override val messagePreview: String = "",
    ) : TtsState

    data class Speaking(
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val messagePreview: String,
    ) : TtsState

    data class Paused(
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val messagePreview: String,
    ) : TtsState

    data class Error(
        val error: TtsError,
        override val chunkIndex: Int,
        override val chunkCount: Int,
        override val messageIndex: Int,
        override val messageCount: Int,
        override val messagePreview: String,
    ) : TtsState
}

enum class TtsError {
    Network,
    Synthesis,
}

/**
 * Pure message-aware sentence queue. The Android TTS owner supplies the two
 * engine operations so queue/progress behavior remains deterministic in tests.
 */
internal class TtsPlaybackQueue(
    private val stopEngine: () -> Unit,
    private val enqueue: (chunk: TtsChunk, utteranceId: String) -> Int,
    private val onTerminal: () -> Unit = {},
) {
    private val _state = MutableStateFlow<TtsState>(TtsState.Idle())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var messages: List<TtsQueuedMessage> = emptyList()
    private var chunks: List<TtsChunk> = emptyList()
    private var messageFirstChunkIndex: IntArray = intArrayOf()
    private var currentIndex = 0
    private var generation = 0L
    private var refreshAtNextBoundary = false
    private var announceSenderForCurrentMessage = false
    private var senderAnnouncedAtMessageIndex: Int? = null

    /**
     * Applies changed enqueue-time parameters (speech rate) at the next chunk
     * boundary. The engine pre-buffers every remaining utterance at enqueue
     * time, so without a re-queue a mid-playback change would never land;
     * re-queueing only at the boundary keeps the current sentence unbroken.
     */
    fun refreshPendingChunksAtNextBoundary() {
        if (_state.value is TtsState.Speaking) refreshAtNextBoundary = true
    }

    fun start(messages: List<TtsQueuedMessage>) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
        replaceMessages(messages)
        currentIndex = 0
        announceSenderForCurrentMessage = false
        senderAnnouncedAtMessageIndex = null
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        enqueueFromCurrentIndex()
    }

    /**
     * Extends an active queue with more messages (auto-read live
     * continuation). No-op when idle or errored — appending must never
     * resurrect a finished session. While speaking, the new chunks enqueue
     * immediately behind the engine's pending utterances; while paused,
     * resume() re-enqueues everything from the current index anyway.
     */
    fun append(moreMessages: List<TtsQueuedMessage>): Boolean {
        val current = _state.value
        val active = current is TtsState.Speaking || current is TtsState.Paused
        if (moreMessages.isEmpty() || !active) return false
        val appended = appendMessages(moreMessages)
        if (current is TtsState.Speaking) {
            publishSpeaking(currentIndex)
            for (chunk in appended) {
                val utteranceId = utteranceId(generation, chunk.index)
                val result = enqueue(spokenChunk(chunk), utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    onError(utteranceId, result)
                    break
                }
            }
        } else {
            publishPaused(currentIndex)
        }
        return true
    }

    fun failBeforePlayback(
        error: TtsError,
        chunkCount: Int,
        messageCount: Int = 0,
        messagePreview: String = "",
    ) {
        fail(
            error = error,
            chunkIndex = 0,
            chunkCount = chunkCount,
            messageIndex = 0,
            messageCount = messageCount,
            messagePreview = messagePreview,
        )
    }

    fun pause() {
        val speaking = _state.value as? TtsState.Speaking ?: return
        stopEngine()
        generation += 1
        currentIndex = speaking.chunkIndex
        publishPaused(currentIndex)
    }

    fun resume() {
        val paused = _state.value as? TtsState.Paused ?: return
        currentIndex = paused.chunkIndex
        announceSenderForCurrentMessage = false
        // The previous engine queue was stopped by pause(). Recompute sender
        // narration when the paused sentence is the first chunk of a message;
        // otherwise a changed speaker can resume without their announcement.
        senderAnnouncedAtMessageIndex = null
        enqueueFromCurrentIndex()
    }

    fun stop() {
        stopEngine()
        generation += 1
        messages = emptyList()
        chunks = emptyList()
        messageFirstChunkIndex = intArrayOf()
        currentIndex = 0
        announceSenderForCurrentMessage = false
        senderAnnouncedAtMessageIndex = null
        _state.value = TtsState.Idle()
    }

    fun skipNext() {
        if (_state.value !is TtsState.Speaking && _state.value !is TtsState.Paused) return
        val currentMessage = messageIndexForChunk(currentIndex)
        val nextMessage = currentMessage + 1
        if (nextMessage >= messages.size) {
            stopEngine()
            generation += 1
            val completedCount = chunks.size
            val completedMessages = messages.size
            val lastPreview = messages.lastOrNull()?.preview.orEmpty()
            messages = emptyList()
            chunks = emptyList()
            messageFirstChunkIndex = intArrayOf()
            currentIndex = completedCount
            announceSenderForCurrentMessage = false
            _state.value =
                TtsState.Idle(
                    chunkIndex = completedCount,
                    chunkCount = completedCount,
                    messageIndex = completedMessages,
                    messageCount = completedMessages,
                    messagePreview = lastPreview,
                )
            onTerminal()
            return
        }
        requeueFrom(firstChunkIndexOfMessage(nextMessage), announceSender = true)
    }

    fun skipPrevious() {
        if (_state.value !is TtsState.Speaking && _state.value !is TtsState.Paused) return
        val currentMessage = messageIndexForChunk(currentIndex)
        val previousMessage = (currentMessage - 1).coerceAtLeast(0)
        requeueFrom(firstChunkIndexOfMessage(previousMessage), announceSender = true)
    }

    fun onDone(utteranceId: String?) {
        val completedIndex = parseCurrentGenerationIndex(utteranceId) ?: return
        if (_state.value !is TtsState.Speaking || completedIndex != currentIndex) return
        val next = completedIndex + 1
        if (next >= chunks.size) {
            val completedCount = chunks.size
            val completedMessages = messages.size
            val lastPreview = messages.lastOrNull()?.preview.orEmpty()
            messages = emptyList()
            chunks = emptyList()
            messageFirstChunkIndex = intArrayOf()
            currentIndex = completedCount
            announceSenderForCurrentMessage = false
            _state.value =
                TtsState.Idle(
                    chunkIndex = completedCount,
                    chunkCount = completedCount,
                    messageIndex = completedMessages,
                    messageCount = completedMessages,
                    messagePreview = lastPreview,
                )
            onTerminal()
        } else {
            currentIndex = next
            announceSenderForCurrentMessage = false
            publishSpeaking(currentIndex)
            if (refreshAtNextBoundary) {
                refreshAtNextBoundary = false
                requeueFrom(next, announceSender = false)
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
        val messageIndex = messageIndexForChunk(failedIndex)
        fail(
            error = error,
            chunkIndex = failedIndex,
            chunkCount = chunks.size,
            messageIndex = messageIndex,
            messageCount = messages.size,
            messagePreview = messages.getOrNull(messageIndex)?.preview.orEmpty(),
        )
    }

    private fun fail(
        error: TtsError,
        chunkIndex: Int,
        chunkCount: Int,
        messageIndex: Int,
        messageCount: Int,
        messagePreview: String,
    ) {
        stopEngine()
        generation += 1
        messages = emptyList()
        chunks = emptyList()
        messageFirstChunkIndex = intArrayOf()
        currentIndex = chunkIndex
        announceSenderForCurrentMessage = false
        _state.value = TtsState.Error(error, chunkIndex, chunkCount, messageIndex, messageCount, messagePreview)
        onTerminal()
    }

    private fun requeueFrom(
        index: Int,
        announceSender: Boolean,
    ) {
        stopEngine()
        generation += 1
        refreshAtNextBoundary = false
        currentIndex = index
        announceSenderForCurrentMessage = announceSender
        senderAnnouncedAtMessageIndex = null
        enqueueFromCurrentIndex()
    }

    private fun enqueueFromCurrentIndex() {
        if (chunks.isEmpty()) {
            _state.value = TtsState.Idle()
            return
        }
        publishSpeaking(currentIndex)
        for (chunk in chunks.drop(currentIndex)) {
            val utteranceId = utteranceId(generation, chunk.index)
            val result = enqueue(spokenChunk(chunk), utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onError(utteranceId, result)
                break
            }
            if (chunk.index == firstChunkIndexOfMessage(messageIndexForChunk(chunk.index))) {
                announceSenderForCurrentMessage = false
            }
        }
    }

    private fun spokenChunk(chunk: TtsChunk): TtsChunk {
        val messageIndex = messageIndexForChunk(chunk.index)
        val message = messages[messageIndex]
        val isFirstChunkOfMessage = chunk.index == firstChunkIndexOfMessage(messageIndex)
        val announced =
            isFirstChunkOfMessage &&
                shouldAnnounceSender(messageIndex) &&
                message.senderDisplayName.isNotBlank()
        if (!announced) return chunk
        senderAnnouncedAtMessageIndex = messageIndex
        return chunk.copy(text = "${message.senderDisplayName}: ${chunk.text}")
    }

    private fun shouldAnnounceSender(messageIndex: Int): Boolean =
        when {
            announceSenderForCurrentMessage -> true
            senderAnnouncedAtMessageIndex == messageIndex -> false
            messageIndex == 0 -> true
            else ->
                !messages[messageIndex].senderKey.equals(messages[messageIndex - 1].senderKey, ignoreCase = true)
        }

    private fun replaceMessages(newMessages: List<TtsQueuedMessage>) {
        messages = newMessages
        rebuildFlatChunks()
    }

    private fun appendMessages(moreMessages: List<TtsQueuedMessage>): List<TtsChunk> {
        val firstAppendedChunkIndex = chunks.size
        messages = messages + moreMessages
        rebuildFlatChunks()
        return chunks.drop(firstAppendedChunkIndex)
    }

    private fun rebuildFlatChunks() {
        val firstIndices = mutableListOf<Int>()
        val flat = mutableListOf<TtsChunk>()
        var nextIndex = 0
        for (message in messages) {
            firstIndices += nextIndex
            for (chunk in message.chunks) {
                flat += chunk.copy(index = nextIndex)
                nextIndex += 1
            }
        }
        chunks = flat
        messageFirstChunkIndex = firstIndices.toIntArray()
    }

    private fun messageIndexForChunk(chunkIndex: Int): Int {
        var messageIndex = 0
        for (index in messageFirstChunkIndex.indices) {
            if (messageFirstChunkIndex[index] <= chunkIndex) messageIndex = index
        }
        return messageIndex
    }

    private fun firstChunkIndexOfMessage(messageIndex: Int): Int = messageFirstChunkIndex[messageIndex]

    private fun publishSpeaking(chunkIndex: Int) {
        val messageIndex = messageIndexForChunk(chunkIndex)
        _state.value =
            TtsState.Speaking(
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                messagePreview = messages[messageIndex].preview,
            )
    }

    private fun publishPaused(chunkIndex: Int) {
        val messageIndex = messageIndexForChunk(chunkIndex)
        _state.value =
            TtsState.Paused(
                chunkIndex = chunkIndex,
                chunkCount = chunks.size,
                messageIndex = messageIndex,
                messageCount = messages.size,
                messagePreview = messages[messageIndex].preview,
            )
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
