package dev.ipf.whitenoise.android.audio.tts

/** Mutable message-relative progress for the active read-aloud queue. */
internal class TtsPlaybackProgress {
    private var progressMessage: TtsQueuedMessage? = null
    private var progressAnonymousMessageIndex: Int? = null
    private var progressChunkIndex: Int? = null
    var fraction = 0f
        private set
    private val spokenPayloadByChunkIndex = mutableMapOf<Int, SpokenPayload>()

    private data class SpokenPayload(
        val textLength: Int,
        val prefixLength: Int,
    )

    fun reset() {
        progressMessage = null
        progressAnonymousMessageIndex = null
        progressChunkIndex = null
        fraction = 0f
        spokenPayloadByChunkIndex.clear()
    }

    fun clearSpokenPayloads() {
        spokenPayloadByChunkIndex.clear()
    }

    fun recordEnqueue(
        chunkIndex: Int,
        spokenTextLength: Int,
        chunkTextLength: Int,
    ) {
        spokenPayloadByChunkIndex[chunkIndex] =
            SpokenPayload(
                textLength = spokenTextLength,
                prefixLength = spokenTextLength - chunkTextLength,
            )
    }

    fun syncBaseline(
        message: TtsQueuedMessage,
        messageIndex: Int,
        chunkIndex: Int,
        sentenceFallback: Float,
    ) {
        val messageChanged =
            progressMessage != message ||
                (message.messageIdHex.isEmpty() && progressAnonymousMessageIndex != messageIndex)
        if (messageChanged) {
            progressMessage = message
            progressAnonymousMessageIndex = messageIndex.takeIf { message.messageIdHex.isEmpty() }
            fraction = sentenceFallback
        } else if (progressChunkIndex != chunkIndex) {
            // A logical sentence may span several engine-safe chunks, and an
            // explicit previous-sentence navigation stays within this message.
            // Neither boundary may erase range progress already observed.
            fraction = maxOf(fraction, sentenceFallback)
        }
        progressChunkIndex = chunkIndex
    }

    fun advanceWithinMessage(chunkEndProgress: Float) {
        fraction = maxOf(fraction, chunkEndProgress)
    }

    fun applyRangeStart(
        chunkIndex: Int,
        start: Int,
        end: Int,
        messageOffsetBeforeChunk: Int,
        messageSpeakableLength: Int,
        sentenceFallback: Float,
    ): Boolean {
        val payload = spokenPayloadByChunkIndex[chunkIndex] ?: return false
        val candidate =
            when {
                start < 0 || end <= start || end > payload.textLength -> sentenceFallback
                else ->
                    TtsMessageProgress.rangeProgress(
                        messageOffsetBeforeChunk = messageOffsetBeforeChunk,
                        rangeStart = start,
                        prefixLength = payload.prefixLength,
                        messageTotalLength = messageSpeakableLength,
                    ) ?: sentenceFallback
            }
        fraction = maxOf(fraction, candidate)
        return true
    }
}
