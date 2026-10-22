package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.ipf.whitenoise.android.ui.conversation.media.voiceSpeedLabel
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Forward10
import dev.ipf.whitenoise.android.ui.icons.filled.Replay10

/** Seek step of the voice banner's back and forward controls; their labels name this ten-second interval. */
internal const val VOICE_TRANSPORT_SEEK_MS = 10_000

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
            onSeekBy = { VoicePlaybackController.seekSessionBy(voice.sessionId, it) },
            onCycleSpeed = { VoicePlaybackController.cycleSessionSpeed(voice.sessionId) },
            onStop = { VoicePlaybackController.stopSession(voice.sessionId) },
            onBodyClick = sourceClick.takeIf { voice.source != null && voice.ready },
        )
    } else {
        TtsTransportBar(appState, onBodyClick = sourceClick)
    }
}

/**
 * Stateless voice transport laid out like the read-aloud strip (#3381): a horizontally scrollable control row with
 * 10-second seeks around a tonal play/pause, the clip speed and stop, then the bounded source label, elapsed time and
 * progress. The source label returns to the message; every control is a separate accessible action.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun VoiceTransportBarContent(
    state: VoicePlaybackController.PlaybackState,
    onPlayingChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onSeekBy: (Int) -> Unit = {},
    onCycleSpeed: () -> Unit = {},
    onBodyClick: (() -> Unit)? = null,
) {
    Surface(
        Modifier.fillMaxWidth().testTag("voice-transport"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { onSeekBy(-VOICE_TRANSPORT_SEEK_MS) }, enabled = state.ready) {
                    Icon(Icons.Default.Replay10, stringResource(R.string.video_seek_back))
                }
                FilledTonalIconButton(onClick = { onPlayingChange(!state.isPlaying) }, enabled = state.ready) {
                    Icon(
                        painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                        stringResource(
                            if (state.isPlaying) R.string.voice_message_pause else R.string.voice_message_play,
                        ),
                    )
                }
                IconButton(onClick = { onSeekBy(VOICE_TRANSPORT_SEEK_MS) }, enabled = state.ready) {
                    Icon(Icons.Default.Forward10, stringResource(R.string.video_seek_forward))
                }
                val speedLabel = voiceSpeedLabel(state.speed)
                val speedDescription = stringResource(R.string.voice_message_speed, speedLabel)
                TextButton(
                    onClick = onCycleSpeed,
                    enabled = state.ready,
                    modifier =
                        Modifier.semantics {
                            contentDescription = speedDescription
                            stateDescription = speedLabel
                        },
                ) {
                    Text(speedLabel)
                }
                IconButton(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.tts_playback_action_stop))
                }
            }
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
            Row(
                Modifier.fillMaxWidth().then(sourceModifier).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.source?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.reply_media_voice),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatVoiceTime(state.positionMs)} / ${formatVoiceTime(state.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
            onSeekBy = {},
            onCycleSpeed = {},
            onBodyClick = {},
        )
    }
}
