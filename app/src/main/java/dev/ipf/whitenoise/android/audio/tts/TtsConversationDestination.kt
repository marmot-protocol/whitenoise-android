package dev.ipf.whitenoise.android.audio.tts

/** Latest safe navigation target for one active conversation-backed TTS session. */
internal data class TtsConversationDestination(
    val accountRef: String,
    val groupIdHex: String,
    val sessionId: Long,
    val passage: TtsPassage,
)

/**
 * Joins independently updated owner and playback state only when they still
 * describe the same active session. A stale owner must never route a newer
 * queue into its account or conversation.
 */
internal fun ttsConversationDestination(
    source: TtsConversationSource?,
    state: TtsState,
): TtsConversationDestination? {
    val passage = state.passage
    val active = state is TtsState.Speaking || state is TtsState.Paused
    val valid =
        active &&
            source != null &&
            source.sessionId == state.sessionId &&
            passage?.messageIdHex?.isNotBlank() == true
    return if (valid) {
        val validSource = requireNotNull(source)
        val validPassage = requireNotNull(passage)
        TtsConversationDestination(
            accountRef = validSource.accountRef,
            groupIdHex = validSource.groupIdHex,
            sessionId = validSource.sessionId,
            passage = validPassage,
        )
    } else {
        null
    }
}
