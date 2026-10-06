package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.matchesPlaybackSession
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.currentPlaybackConversationDestination
import dev.ipf.whitenoise.android.state.observePlaybackConversationDestination
import dev.ipf.whitenoise.android.state.useVoiceTransport
import dev.ipf.whitenoise.android.ui.conversation.media.formatVoiceTime

/** One compact transport selects the current audio owner in either the shell or normal chat-list flow. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackTransportBar(
    appState: WhiteNoiseAppState,
    onBodyClick: (() -> Unit)? = null,
) {
    val destination = appState.observePlaybackConversationDestination()
    val sourceClick =
        onBodyClick?.takeIf { destination != null }?.let { callback ->
            {
                val current = appState.currentPlaybackConversationDestination()
                if (destination?.matchesPlaybackSession(current) == true) callback()
            }
        }
    val voice by VoicePlaybackController.state.collectAsState()
    val speech by appState.ttsController.state.collectAsState()
    val ownerAvailable =
        voice.source?.let { source ->
            appState.accounts.any { it.label == source.accountRef && !it.signedOut }
        } ?: true
    LaunchedEffect(voice.sessionId, ownerAvailable) {
        if (!ownerAvailable) VoicePlaybackController.stopSession(voice.sessionId)
    }
    if (appState.appLockScreenVisible) return
    if (useVoiceTransport(voice, speech)) {
        if (!ownerAvailable) return
        VoiceTransportBarContent(
            state = voice,
            onPlayingChange = { VoicePlaybackController.setSessionPlaying(voice.sessionId, it) },
            onStop = { VoicePlaybackController.stopSession(voice.sessionId) },
            onBodyClick = sourceClick.takeIf { voice.source != null && voice.ready },
        )
    } else {
        TtsTransportBar(appState, onBodyClick = sourceClick)
    }
}

/** Stateless voice transport with independent accessible controls and a bounded source label. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceTransportBarContent(
    state: VoicePlaybackController.PlaybackState,
    onPlayingChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onBodyClick: (() -> Unit)? = null,
) {
    Surface(
        Modifier.fillMaxWidth().testTag("voice-transport"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val sourceModifier =
                    if (onBodyClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.tts_bar_return_to_source),
                            onClick = onBodyClick,
                        )
                    }
                Column(Modifier.weight(1f).then(sourceModifier).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        state.source?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.reply_media_voice),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatVoiceTime(state.positionMs)} / ${formatVoiceTime(state.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onPlayingChange(!state.isPlaying) }, enabled = state.ready) {
                    Icon(
                        painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        stringResource(
                            if (state.isPlaying) R.string.voice_message_pause else R.string.voice_message_play,
                        ),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.tts_playback_action_stop))
                }
            }
            LinearProgressIndicator(
                progress = { (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Static design preview uses the same stateless renderer as the live shell. */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
@Suppress("FunctionNaming")
private fun VoiceTransportPreview() {
    dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme {
        VoiceTransportBarContent(
            state =
                VoicePlaybackController.PlaybackState(
                    key = "preview",
                    isPlaying = true,
                    positionMs = 12_000,
                    durationMs = 45_000,
                    ready = true,
                    source =
                        dev.ipf.whitenoise.android.audio
                            .VoicePlaybackSource("personal", "group", "message", "Maya"),
                ),
            onPlayingChange = {},
            onStop = {},
            onBodyClick = {},
        )
    }
}
