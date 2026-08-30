package dev.ipf.whitenoise.android.audio.tts

/** Immutable flattened queue indexes rebuilt whenever the message window changes. */
internal data class TtsQueueProjection(
    val chunks: List<TtsChunk>,
    val messageFirstChunkIndex: List<Int>,
    val messageSentenceCount: List<Int>,
) {
    fun messageIndexForChunk(chunkIndex: Int): Int {
        // Binary search over sorted first-chunk offsets avoids quadratic
        // reflattens as the paged window grows.
        var low = 0
        var high = messageFirstChunkIndex.size - 1
        var messageIndex = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (messageFirstChunkIndex[mid] <= chunkIndex) {
                messageIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return messageIndex
    }

    fun firstChunkIndexOfMessage(messageIndex: Int): Int = messageFirstChunkIndex[messageIndex]

    fun firstChunkIndexOfSentence(
        messageIndex: Int,
        sentenceIndex: Int,
    ): Int? {
        val first = firstChunkIndexOfMessage(messageIndex)
        val endExclusive =
            messageFirstChunkIndex.getOrNull(messageIndex + 1) ?: chunks.size
        return (first until endExclusive).firstOrNull { chunks[it].sentenceIndex == sentenceIndex }
    }

    fun firstChunkIndexOfSentenceContaining(chunkIndex: Int): Int {
        var index = chunkIndex
        while (index > 0 && inSameSentence(index - 1, chunkIndex)) index--
        return index
    }

    fun firstChunkIndexAfterSentenceContaining(chunkIndex: Int): Int {
        var index = chunkIndex + 1
        while (index < chunks.size && inSameSentence(index, chunkIndex)) index++
        return index
    }

    private fun inSameSentence(
        first: Int,
        second: Int,
    ): Boolean =
        messageIndexForChunk(first) == messageIndexForChunk(second) &&
            chunks[first].sentenceIndex == chunks[second].sentenceIndex

    companion object {
        val EMPTY = TtsQueueProjection(emptyList(), emptyList(), emptyList())

        fun from(messages: List<TtsQueuedMessage>): TtsQueueProjection {
            val firstIndices = mutableListOf<Int>()
            val sentenceCounts = mutableListOf<Int>()
            val flat = mutableListOf<TtsChunk>()
            var nextIndex = 0
            for (message in messages) {
                // Empty messages duplicate first-chunk indices and alias targets.
                require(message.chunks.isNotEmpty()) { "queued messages must contain at least one chunk" }
                firstIndices += nextIndex
                sentenceCounts += (message.chunks.maxOfOrNull(TtsChunk::sentenceIndex) ?: -1) + 1
                for (chunk in message.chunks) {
                    flat +=
                        chunk.copy(
                            index = nextIndex,
                            messageIdHex = message.messageIdHex,
                            projectionId = message.projectionId,
                            timelineAt = message.timelineAt,
                        )
                    nextIndex += 1
                }
            }
            return TtsQueueProjection(flat, firstIndices.toList(), sentenceCounts.toList())
        }
    }
}
