package dev.ipf.whitenoise.android.audio.tts

internal fun speakingTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
    sentenceIndex: Int = 0,
    sentenceCount: Int = 1,
    messageProgressFraction: Float? = null,
): TtsState.Speaking =
    TtsState.Speaking(
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        messageIndex = messageIndex,
        messageCount = messageCount,
        sentenceIndexWithinMessage = sentenceIndex,
        sentenceCountWithinMessage = sentenceCount,
        messagePreview = messagePreview,
        messageProgressFraction = messageProgressFraction ?: sentenceProgressFallback(sentenceIndex, sentenceCount),
    )

internal fun pausedTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
    sentenceIndex: Int = 0,
    sentenceCount: Int = 1,
    messageProgressFraction: Float? = null,
): TtsState.Paused =
    TtsState.Paused(
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        messageIndex = messageIndex,
        messageCount = messageCount,
        sentenceIndexWithinMessage = sentenceIndex,
        sentenceCountWithinMessage = sentenceCount,
        messagePreview = messagePreview,
        messageProgressFraction = messageProgressFraction ?: sentenceProgressFallback(sentenceIndex, sentenceCount),
    )

internal fun idleTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
    sentenceIndex: Int = 0,
    sentenceCount: Int = 0,
    messageProgressFraction: Float? = null,
): TtsState.Idle =
    TtsState.Idle(
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        messageIndex = messageIndex,
        messageCount = messageCount,
        sentenceIndexWithinMessage = sentenceIndex,
        sentenceCountWithinMessage = sentenceCount,
        messagePreview = messagePreview,
        messageProgressFraction = messageProgressFraction ?: sentenceProgressFallback(sentenceIndex, sentenceCount),
    )

internal fun errorTts(
    error: TtsError,
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
    sentenceIndex: Int = 0,
    sentenceCount: Int = 0,
    messageProgressFraction: Float? = null,
): TtsState.Error =
    TtsState.Error(
        error = error,
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        messageIndex = messageIndex,
        messageCount = messageCount,
        sentenceIndexWithinMessage = sentenceIndex,
        sentenceCountWithinMessage = sentenceCount,
        messagePreview = messagePreview,
        messageProgressFraction = messageProgressFraction ?: sentenceProgressFallback(sentenceIndex, sentenceCount),
    )

private fun sentenceProgressFallback(
    sentenceIndex: Int,
    sentenceCount: Int,
): Float = TtsMessageProgress.sentenceFallback(sentenceIndex, sentenceCount)
