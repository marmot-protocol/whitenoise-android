package dev.ipf.whitenoise.android.audio

/** A displayed recovery action is a receipt, never permission to replace a later session. */
internal data class ConversationDictationComposerAccess(
    val sessionId: Long?,
    val actionGeneration: Long,
    val phase: ConversationDictationComposerPhase,
    val failure: ConversationDictationFailure? = null,
)

internal enum class ConversationDictationComposerPhase {
    Ready,
    RemainingAudio,
    Transcribing,
    ClosingMicrophone,
    AudioStateUnavailable,
    Protected,
    TargetUnavailable,
    OtherConversation,
}
