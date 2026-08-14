package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.ttsConversationDestination

/** Snapshot used by shell navigation; mismatched/replaced sessions fail closed. */
internal fun WhiteNoiseAppState.currentTtsConversationDestination(): TtsConversationDestination? =
    ttsConversationDestination(
        source = ttsHistorySession.conversationSource.value,
        state = ttsController.state.value,
    )
