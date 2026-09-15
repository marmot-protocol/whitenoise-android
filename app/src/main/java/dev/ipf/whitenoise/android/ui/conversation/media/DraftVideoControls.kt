@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.ipf.whitenoise.android.R

private val TRANSPORT_ICON = 32.dp

/** Toggles playback, restarting from the head once the clip has run to its end. */
@Composable
internal fun DraftVideoPlayPauseButton(player: ExoPlayer) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var ended by remember(player) { mutableStateOf(player.playbackState == Player.STATE_ENDED) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    source: Player,
                    events: Player.Events,
                ) {
                    playing = source.isPlaying
                    ended = source.playbackState == Player.STATE_ENDED
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DraftVideoIconButton(
        icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
        description = stringResource(if (playing) R.string.video_pause else R.string.video_play),
        testTag = "conversation.media.preview.video.playpause",
    ) {
        when {
            playing -> player.pause()
            ended -> {
                player.seekTo(0L)
                player.play()
            }
            else -> player.play()
        }
    }
}

/** Steps playback back by the fixed seek interval, never below the head of the clip. */
@Composable
internal fun DraftVideoSeekBackButton(player: ExoPlayer) {
    DraftVideoIconButton(
        icon = Icons.Default.Replay10,
        description = stringResource(R.string.video_seek_back),
        testTag = "conversation.media.preview.video.back",
    ) {
        player.seekTo((player.currentPosition - DRAFT_VIDEO_SEEK_STEP_MS).coerceAtLeast(0L))
    }
}

/** Steps playback forward by the fixed seek interval, stopping at the end of the clip. */
@Composable
internal fun DraftVideoSeekForwardButton(player: ExoPlayer) {
    DraftVideoIconButton(
        icon = Icons.Default.Forward10,
        description = stringResource(R.string.video_seek_forward),
        testTag = "conversation.media.preview.video.forward",
    ) {
        val limit = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + DRAFT_VIDEO_SEEK_STEP_MS).coerceAtMost(limit))
    }
}

/** Cycles the playback rate and labels itself with the rate now in effect. */
@Composable
internal fun DraftVideoSpeedButton(player: ExoPlayer) {
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    val label = stringResource(R.string.video_playback_speed)
    TextButton(
        onClick = {
            val next = nextPlaybackSpeed(speed)
            player.setPlaybackSpeed(next)
            speed = next
        },
        modifier =
            Modifier
                .semantics { contentDescription = label }
                .testTag("conversation.media.preview.video.speed"),
    ) {
        Text(playbackSpeedLabel(speed))
    }
}

/** Silences or restores the clip's audio without disturbing its position. */
@Composable
internal fun DraftVideoMuteButton(player: ExoPlayer) {
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    DraftVideoIconButton(
        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        description = stringResource(if (muted) R.string.video_unmute else R.string.video_mute),
        testTag = "conversation.media.preview.video.mute",
    ) {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }
}

/** Shared transport button shape: one icon, one description and one stable test identity. */
@Composable
private fun DraftVideoIconButton(
    icon: ImageVector,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(TRANSPORT_ICON))
    }
}
