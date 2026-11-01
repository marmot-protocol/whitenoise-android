package dev.ipf.whitenoise.android.audio

internal enum class VoiceRecordingStopReason {
    LimitReached,
    Interrupted,
}

/** Encoder failure after an automatic stop must not be presented as a short take. */
internal class VoiceRecordingSaveException : IllegalStateException("Interrupted recording could not be saved")
