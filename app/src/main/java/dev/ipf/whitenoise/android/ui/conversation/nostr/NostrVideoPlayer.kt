@file:Suppress("FunctionNaming")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.ui.common.nativePageBody
import dev.ipf.whitenoise.android.ui.common.nativePageHeader
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.media.localVideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Hosts the referenced video in its own window and uses normal dismissal when returning to a playback source. */
@Composable
internal fun NostrVideoPlayerDialog(
    mediaUrl: String,
    onDismiss: () -> Unit,
    origin: Rect? = null,
) {
    MeasuredPlaybackDialog(
        origin = origin,
        ownerKey = mediaUrl,
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        NostrVideoPlayback(mediaUrl, onDismiss = dismiss)
    }
}

/**
 * Downloads the sender-chosen video before any parser sees it, then plays the private copy through the isolated
 * attachment player. Each open player owns one directory, deleted with the download when the player leaves.
 */
@Composable
private fun NostrVideoPlayback(
    mediaUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val directory =
        remember(mediaUrl) { File(nostrVideoDirectory(context.cacheDir), UUID.randomUUID().toString()) }
    var attempt by remember(directory) { mutableIntStateOf(0) }
    var download by remember(directory) { mutableStateOf<File?>(null) }
    var failed by remember(directory) { mutableStateOf(false) }
    // Consumed synchronously, so one failure starts at most one new download.
    val retry = {
        if (failed) {
            failed = false
            download = null
            attempt++
        }
    }
    LaunchedEffect(directory) {
        try {
            snapshotFlow { attempt }.collect {
                val ready =
                    withContext(Dispatchers.IO) {
                        // A retry replaces the copy that failed to play; its released player no longer reads it.
                        directory.listFiles()?.forEach(File::delete)
                        directory.isDirectory || directory.mkdirs()
                    }
                val file = if (ready) downloadNostrVideo(directory, mediaUrl) else null
                download = file
                failed = file == null
            }
        } finally {
            // Runs only after the download has returned or aborted, so no file can appear after this deletion.
            withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
        }
    }
    val file = download
    if (file == null) {
        NostrVideoPlayerSurface(player = null, playbackFailed = failed, onRetry = retry, onDismiss = onDismiss)
    } else {
        val player = rememberNostrVideoPlayer(file) { failed = true }
        NostrVideoPlayerSurface(player = player, playbackFailed = failed, onRetry = retry, onDismiss = onDismiss)
    }
}

/** Plays a downloaded copy; compressed parsing stays in the isolated decoder behind [localVideoPlayer]. */
@Composable
private fun rememberNostrVideoPlayer(
    file: File,
    onPlaybackFailed: () -> Unit,
): Player {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player =
        remember(file) {
            requireNotNull(localVideoPlayer(context, Uri.fromFile(file))).apply {
                setAudioAttributes(nostrVideoAudioAttributes, true)
                // Unplugging headphones pauses instead of moving the clip to the speaker.
                setHandleAudioBecomingNoisy(true)
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) = onPlaybackFailed()
                    },
                )
            }
        }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(lifecycleOwner, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.pause()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        VoicePlaybackController.pause()
        player.prepare()
        // A download can finish after ON_STOP, which this observer never sees; only a started screen plays.
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    return player
}

@Composable
private fun NostrVideoPlayerSurface(
    player: Player?,
    playbackFailed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().nativePageBody().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = VIDEO_CONTROLS_TIMEOUT_MILLIS
                    }
                },
                update = { playerView -> playerView.player = player },
                onRelease = { playerView -> playerView.player = null },
            )
        } else if (!playbackFailed) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.testTag(NOSTR_VIDEO_DOWNLOADING_TAG))
        }
        if (playbackFailed) NostrVideoPlaybackFailure(onRetry)
        IconButton(
            onClick = onDismiss,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .nativePageHeader()
                    .statusBarsPadding()
                    .padding(8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.close),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun NostrVideoPlaybackFailure(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.78f), MaterialTheme.shapes.large)
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.nostr_video_playback_failed),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.retry), color = Color.White)
        }
    }
}

private val nostrVideoAudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

private const val VIDEO_CONTROLS_TIMEOUT_MILLIS = 2_500

internal const val NOSTR_VIDEO_DOWNLOADING_TAG = "nostr-video-downloading"
