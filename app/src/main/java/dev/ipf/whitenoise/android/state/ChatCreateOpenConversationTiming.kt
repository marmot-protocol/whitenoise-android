package dev.ipf.whitenoise.android.state

internal data class ChatCreateOpenConversationTimingState(
    val frameReadyMarked: Boolean = false,
    val composerReadyMarked: Boolean = false,
)

internal sealed interface ChatCreateOpenConversationTimingEvent {
    data object ConversationFrameCommitted : ChatCreateOpenConversationTimingEvent

    data object ComposerReady : ChatCreateOpenConversationTimingEvent
}

internal fun reduceChatCreateOpenConversationTiming(
    state: ChatCreateOpenConversationTimingState,
    event: ChatCreateOpenConversationTimingEvent,
): ChatCreateOpenConversationTimingState =
    when (event) {
        ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted ->
            if (state.frameReadyMarked) {
                state
            } else {
                state.copy(frameReadyMarked = true)
            }
        ChatCreateOpenConversationTimingEvent.ComposerReady ->
            if (!state.frameReadyMarked || state.composerReadyMarked) {
                state
            } else {
                state.copy(composerReadyMarked = true)
            }
    }

internal fun chatCreateOpenConversationTimingStage(
    state: ChatCreateOpenConversationTimingState,
    event: ChatCreateOpenConversationTimingEvent,
): String? =
    when (event) {
        ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted ->
            ChatCreateOpenTiming.STAGE_CONVERSATION_FRAME_READY.takeIf { !state.frameReadyMarked }
        ChatCreateOpenConversationTimingEvent.ComposerReady ->
            ChatCreateOpenTiming.STAGE_COMPOSER_READY.takeIf {
                state.frameReadyMarked && !state.composerReadyMarked
            }
    }
