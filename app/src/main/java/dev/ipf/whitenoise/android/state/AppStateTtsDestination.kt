package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.audio.PlaybackConversationDestination
import dev.ipf.whitenoise.android.audio.VoiceConversationDestination
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.ttsConversationDestination
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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

/** The shell and its click handler choose the same current audio owner; paused speech yields to a voice note. */
internal fun useVoiceTransport(
    voice: VoicePlaybackController.PlaybackState,
    speech: TtsState,
): Boolean =
    voice.key != null &&
        speech !is TtsState.Speaking &&
        speech !is TtsState.Preparing

/** Re-reads source ownership at every asynchronous navigation boundary. */
internal fun WhiteNoiseAppState.currentPlaybackConversationDestination(): PlaybackConversationDestination? {
    val voice = VoicePlaybackController.state.value
    return if (useVoiceTransport(voice, ttsController.state.value)) {
        voice.source?.takeIf { voice.ready && voice.sessionId > 0L }?.let {
            VoiceConversationDestination(it, voice.sessionId)
        }
    } else {
        currentTtsConversationDestination()
            ?: attachmentSpeechDestination.value?.current(ttsController.state.value)
    }
}

/** Observes source changes without recomposing the full shell for every audio-position tick. */
@Composable
internal fun WhiteNoiseAppState.observePlaybackConversationDestination(): PlaybackConversationDestination? {
    val destinations =
        remember(this) {
            combine(
                VoicePlaybackController.state,
                ttsHistorySession.conversationSource,
                ttsController.state,
                attachmentSpeechDestination,
            ) { voice, source, speech, attachment ->
                if (useVoiceTransport(voice, speech)) {
                    voice.source?.takeIf { voice.ready && voice.sessionId > 0L }?.let {
                        VoiceConversationDestination(it, voice.sessionId)
                    }
                } else {
                    ttsConversationDestination(source, speech) ?: attachment?.current(speech)
                }
            }.distinctUntilChanged()
        }
    val destination by destinations.collectAsState(initial = currentPlaybackConversationDestination())
    return destination
}

/** Shell/modal layout changes only when transport presence changes; position ticks belong to the strip. */
@Composable
internal fun WhiteNoiseAppState.observePlaybackTransportVisible(): Boolean {
    val visibility =
        remember(this) {
            combine(VoicePlaybackController.state, ttsController.state) { voice, speech ->
                voice.key != null || speech !is TtsState.Idle
            }.distinctUntilChanged()
        }
    val initialVisibility = remember(this) { currentPlaybackTransportVisible() }
    val visible by visibility.collectAsState(initial = initialVisibility)
    return visible
}

/** Seeds the filtered observer once; later visibility changes are delivered by its collected flow. */
private fun WhiteNoiseAppState.currentPlaybackTransportVisible(): Boolean =
    VoicePlaybackController.state.value.key != null || ttsController.state.value !is TtsState.Idle
