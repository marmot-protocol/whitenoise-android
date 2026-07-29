package dev.ipf.whitenoise.android.audio.tts

internal fun speakingTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
): TtsState.Speaking = TtsState.Speaking(chunkIndex, chunkCount, messageIndex, messageCount, messagePreview)

internal fun pausedTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
): TtsState.Paused = TtsState.Paused(chunkIndex, chunkCount, messageIndex, messageCount, messagePreview)

internal fun idleTts(
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
): TtsState.Idle = TtsState.Idle(chunkIndex, chunkCount, messageIndex, messageCount, messagePreview)

internal fun errorTts(
    error: TtsError,
    chunkIndex: Int,
    chunkCount: Int,
    messageIndex: Int = 0,
    messageCount: Int = 1,
    messagePreview: String = "",
): TtsState.Error = TtsState.Error(error, chunkIndex, chunkCount, messageIndex, messageCount, messagePreview)
