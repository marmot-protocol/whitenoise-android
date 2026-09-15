@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay

/** Seek buttons jump a fixed ten seconds, matching the prototype's transport. */
internal const val DRAFT_VIDEO_SEEK_STEP_MS = 10_000L

/** Below this height the centre transport would collide with the frame, so it moves into the bottom row. */
internal val DRAFT_VIDEO_COMPACT_HEIGHT = 480.dp

private const val PROGRESS_POLL_MILLIS = 200L
private val CONTROL_SPACING = 8.dp
private val PLAYBACK_SPEEDS = listOf(1f, 1.5f, 2f, 0.5f)

private val draftVideoAudioAttributes =
    AudioAttributes
        .Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

/** Picks the next speed in the prototype's 1× → 1.5× → 2× → 0.5× cycle. */
internal fun nextPlaybackSpeed(current: Float): Float {
    val index = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return PLAYBACK_SPEEDS[(index + 1).mod(PLAYBACK_SPEEDS.size)]
}

/** One decimal place is all a speed toggle ever shows. */
private const val SPEED_LABEL_SCALE = 10

/** Formats a speed for its toggle label without trailing zeros ("1×", "1.5×"). */
internal fun playbackSpeedLabel(speed: Float): String {
    val rounded = (speed * SPEED_LABEL_SCALE).toInt()
    val whole = rounded / SPEED_LABEL_SCALE
    val tenths = rounded % SPEED_LABEL_SCALE
    return if (tenths == 0) "$whole×" else "$whole.$tenths×"
}

/**
 * One staged clip playing inside the attachment preview. Only the settled pager page is [active], so a
 * single decoder and one audio focus request are alive at a time; every other page keeps its poster.
 */
@Composable
@Suppress("LongParameterList")
internal fun DraftVideoPreview(
    uri: Uri,
    active: Boolean,
    controlsVisible: Boolean,
    bottomControlsInset: Dp,
    onToggleControls: () -> Unit,
    onShowControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = rememberDraftVideoPlayer(uri, active)
    val context = LocalContext.current
    val touchExploration =
        remember(context) {
            context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        }
    var buffering by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    DisposableEffect(player, active) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    source: Player,
                    events: Player.Events,
                ) {
                    buffering = source.playbackState == Player.STATE_BUFFERING
                    playing = source.isPlaying
                    if (active && !source.isPlaying) onShowControls()
                }
            }
        player?.addListener(listener)
        buffering = player?.playbackState == Player.STATE_BUFFERING
        playing = player?.isPlaying == true
        onDispose { player?.removeListener(listener) }
    }
    KeepScreenOnWhilePlaying(playing)
    BoxWithConstraints(modifier) {
        val compact = maxHeight < DRAFT_VIDEO_COMPACT_HEIGHT
        DraftVideoSurface(
            player = player,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(touchExploration) {
                        detectTapGestures(onTap = { if (!touchExploration) onToggleControls() })
                    }.testTag("conversation.media.preview.video.surface"),
        )
        if (player != null && controlsVisible && active) {
            if (!compact) {
                DraftVideoTransport(
                    player = player,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            DraftVideoBottomBar(
                player = player,
                buffering = buffering,
                compact = compact,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottomControlsInset),
            )
        }
    }
}

/** Holds the window awake for the duration of playback and restores the previous flag afterwards. */
@Composable
private fun KeepScreenOnWhilePlaying(playing: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, playing) {
        val previous = view.keepScreenOn
        if (playing) view.keepScreenOn = true
        onDispose { if (playing) view.keepScreenOn = previous }
    }
}

/**
 * Creates the player only while this page is [active] and the host is resumed, releasing it (and
 * remembering its position, volume and speed) as soon as either stops holding.
 */
@Composable
internal fun rememberDraftVideoPlayer(
    uri: Uri,
    active: Boolean,
): ExoPlayer? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by rememberSaveable(uri) { mutableLongStateOf(0L) }
    var volume by rememberSaveable(uri) { mutableFloatStateOf(1f) }
    var speed by rememberSaveable(uri) { mutableFloatStateOf(1f) }
    var player by remember(uri) { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(context, lifecycle, uri, active) {
        fun release() {
            player?.let { live ->
                position = live.currentPosition.coerceAtLeast(0L)
                volume = live.volume
                speed = live.playbackParameters.speed
                player = null
                live.release()
            }
        }

        fun update() {
            val holds = active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!holds) {
                release()
            } else if (player == null) {
                player =
                    ExoPlayer
                        .Builder(context)
                        .setAudioAttributes(draftVideoAudioAttributes, true)
                        .setHandleAudioBecomingNoisy(true)
                        .build()
                        .apply {
                            setMediaItem(MediaItem.fromUri(uri), position)
                            this.volume = volume
                            setPlaybackSpeed(speed)
                            playWhenReady = false
                            prepare()
                        }
            }
        }

        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            release()
        }
    }
    return player
}

/** Renders the decoder output without Media3's own controller; the prototype owns every affordance. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun DraftVideoSurface(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}

/** Centre transport: rewind, play/pause and fast-forward, shown only when the frame is tall enough. */
@Composable
private fun DraftVideoTransport(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DraftVideoSeekBackButton(player)
        DraftVideoPlayPauseButton(player)
        DraftVideoSeekForwardButton(player)
    }
}

/** Bottom bar: buffering line, scrub slider, and the speed and mute toggles pinned to its end. */
@Composable
private fun DraftVideoBottomBar(
    player: ExoPlayer,
    buffering: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("conversation.media.preview.video.controls"),
        verticalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
    ) {
        if (buffering) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .testTag("conversation.media.preview.video.buffering"),
            )
        }
        DraftVideoSeekSlider(player)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else CONTROL_SPACING, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                DraftVideoSeekBackButton(player)
                DraftVideoPlayPauseButton(player)
                DraftVideoSeekForwardButton(player)
                Spacer(Modifier.weight(1f))
            }
            DraftVideoSpeedButton(player)
            DraftVideoMuteButton(player)
        }
    }
}

/** Scrub bar without an end-stop marker; dragging holds a local value until the pointer lifts. */
@Composable
private fun DraftVideoSeekSlider(player: ExoPlayer) {
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.takeIf { it > 0L } ?: 0L
            position = player.currentPosition.coerceAtLeast(0L)
            delay(PROGRESS_POLL_MILLIS)
        }
    }
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val label = stringResource(R.string.video_playback_position)
    Slider(
        value = scrub ?: fraction,
        onValueChange = { scrub = it },
        onValueChangeFinished = {
            scrub?.let { player.seekTo((it * duration).toLong()) }
            scrub = null
        },
        enabled = duration > 0L,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .testTag("conversation.media.preview.video.seek"),
        track = { state ->
            SliderDefaults.Track(sliderState = state, enabled = duration > 0L, drawStopIndicator = null)
        },
    )
}
