package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.ttsConversationDestination

/** Snapshot used by shell navigation; mismatched/replaced sessions fail closed. */
internal fun WhiteNoiseAppState.currentTtsConversationDestination(): TtsConversationDestination? =
    ttsConversationDestination(
        source = ttsHistorySession.conversationSource.value,
        state = ttsController.state.value,
    )

/** Compose-observed counterpart used while a shell route must react to stop, replacement, or passage advance. */
@Composable
internal fun WhiteNoiseAppState.observeTtsConversationDestination(): TtsConversationDestination? {
    val source by ttsHistorySession.conversationSource.collectAsState()
    val state by ttsController.state.collectAsState()
    return ttsConversationDestination(source = source, state = state)
}
