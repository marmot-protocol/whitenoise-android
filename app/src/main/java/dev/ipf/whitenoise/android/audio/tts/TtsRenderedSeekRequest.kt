package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSpeechMessage

/** One visible-message intent, resolved with the same prepared speech used for playback. */
internal data class TtsRenderedSeekRequest(
    val entry: TtsSpeakableEntry,
    val sentenceIndex: (PreparedSpeechMessage) -> Int?,
    val canCommit: () -> Boolean,
)
