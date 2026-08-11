package dev.ipf.whitenoise.android.audio.tts

/** Deterministic message-relative progress helpers for the read-aloud queue. */
internal object TtsMessageProgress {
    fun sentenceFallback(
        sentenceIndex: Int,
        sentenceCount: Int,
    ): Float {
        if (sentenceCount <= 0) return 0f
        return sentenceIndex.toFloat() / sentenceCount.toFloat()
    }

    fun rangeProgress(
        messageOffsetBeforeChunk: Int,
        rangeStart: Int,
        prefixLength: Int,
        messageTotalLength: Int,
    ): Float? {
        if (messageTotalLength <= 0) return null
        val adjustedStart = rangeStart - prefixLength
        val offset = messageOffsetBeforeChunk + adjustedStart
        return if (adjustedStart >= 0 && offset in 0..messageTotalLength) {
            (offset.toFloat() / messageTotalLength.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    fun chunkEndProgress(
        messageOffsetBeforeChunk: Int,
        chunkLength: Int,
        messageTotalLength: Int,
    ): Float {
        if (messageTotalLength <= 0) return 0f
        val endOffset = messageOffsetBeforeChunk + chunkLength
        return (endOffset.toFloat() / messageTotalLength.toFloat()).coerceIn(0f, 1f)
    }
}
