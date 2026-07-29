package dev.ipf.whitenoise.android.audio.tts

/** One navigable speakable message and its sentence chunks in the playback queue. */
internal data class TtsQueuedMessage(
    val senderKey: String,
    val senderDisplayName: String,
    val preview: String,
    val chunks: List<TtsChunk>,
)
