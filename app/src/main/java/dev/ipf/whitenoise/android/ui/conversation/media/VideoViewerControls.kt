@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Pause
import dev.ipf.whitenoise.android.ui.icons.filled.PlayArrow
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.delay

internal const val VIDEO_VIEWER_SURFACE_TAG = "conversation.media.viewer.video.surface"
internal const val VIDEO_VIEWER_CONTROLS_TAG = "conversation.media.viewer.video.controls"
internal const val VIDEO_VIEWER_PLAY_PAUSE_TAG = "conversation.media.viewer.video.playPause"
internal const val VIDEO_VIEWER_SEEK_TAG = "conversation.media.viewer.video.seek"
internal const val VIDEO_VIEWER_ELAPSED_TAG = "conversation.media.viewer.video.elapsed"
internal const val VIDEO_VIEWER_TOTAL_TAG = "conversation.media.viewer.video.total"
internal const val VIDEO_VIEWER_BUFFERING_TAG = "conversation.media.viewer.video.buffering"

/** How often visible scrub bars read the player's position. */
internal const val VIDEO_PROGRESS_POLL_MILLIS = 200L

/** How long the viewer's playback controls and chrome stay up during playback after the last touch. */
private const val VIDEO_VIEWER_CONTROLS_TIMEOUT_MS = 2_500

/** The controls show icons, time text and interactive controls. */
private const val VIDEO_VIEWER_CONTROLS_CONTENT =
    AccessibilityManager.FLAG_CONTENT_CONTROLS or
        AccessibilityManager.FLAG_CONTENT_ICONS or
        AccessibilityManager.FLAG_CONTENT_TEXT

private val PLAY_PAUSE_ICON = 32.dp
private val PLAY_PAUSE_TARGET = 56.dp

/** Material's slider measures 44dp; the seek bar reserves and reports the 48dp minimum touch height. */
private val SEEK_MIN_TOUCH_HEIGHT = 48.dp

/** The unplayed part of the viewer's seek track, dimmed from the chrome's content colour. */
private const val SEEK_INACTIVE_TRACK_ALPHA = 0.38f

/**
 * The viewer's video page body (#3801). The player keeps one fixed, letterboxed size while compact Compose controls
 * fade over it: a centred play/pause and a time row with a scrub bar just above the bottom actions.
 *
 * [controlsVisible] is owned by the viewer, so the controls and the chrome show and hide together. A tap toggles
 * them; playback hides them after the accessibility-adjusted timeout. A paused, ended or idle player shows them again
 * and never times them out, though a tap may still hide them, as Media3's own controller allowed.
 */
@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun VideoViewerSurface(
    player: Player,
    controlsVisible: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestOnControlsVisibleChange by rememberUpdatedState(onControlsVisibleChange)
    val latestControlsVisible by rememberUpdatedState(controlsVisible)
    val playback = rememberViewerPlayback(player)
    val playWhenReady = playback.playWhenReady
    val playbackState = playback.playbackState
    var scrubbing by remember(player) { mutableStateOf(false) }
    val forced = !playWhenReady || playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE
    val touchExploration =
        remember(context) {
            context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        }
    LaunchedEffect(forced) { if (forced) latestOnControlsVisibleChange(true) }
    // TalkBack users reach the controls by swiping through them, so they never time out from under the focus.
    LaunchedEffect(controlsVisible, forced, scrubbing, touchExploration) {
        val holdsControls = forced || scrubbing || touchExploration
        if (controlsVisible && !holdsControls) {
            delay(viewerControllerTimeoutMs(context).toLong())
            latestOnControlsVisibleChange(false)
        }
    }
    val toggle = { latestOnControlsVisibleChange(!latestControlsVisible) }
    val toggleLabel = stringResource(R.string.media_viewer_toggle_controls)
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag(VIDEO_VIEWER_SURFACE_TAG),
            factory = { ctx -> viewerPlayerView(ctx, player) },
            update = { playerView -> playerView.player = player },
            onRelease = { playerView -> playerView.player = null },
        )
        VideoViewerTapLayer(toggle, toggleLabel, Modifier.matchParentSize())
        if (playWhenReady && playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(PLAY_PAUSE_TARGET).testTag(VIDEO_VIEWER_BUFFERING_TAG),
                color = Color.White,
            )
        }
        VideoViewerPlaybackControls(
            player = player,
            visible = controlsVisible,
            onScrubbingChange = { scrubbing = it },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** The two player fields the viewer controls react to, refreshed on every player event. */
private data class ViewerPlayback(
    val playWhenReady: Boolean,
    val playbackState: Int,
)

@Composable
private fun rememberViewerPlayback(player: Player): ViewerPlayback {
    var playback by remember(player) { mutableStateOf(ViewerPlayback(player.playWhenReady, player.playbackState)) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    source: Player,
                    events: Player.Events,
                ) {
                    playback = ViewerPlayback(source.playWhenReady, source.playbackState)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return playback
}

/** The tap target that toggles the controls, with the same toggle offered to TalkBack as a custom action. */
@Composable
private fun VideoViewerTapLayer(
    toggle: () -> Unit,
    toggleLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) }
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            toggle()
                            true
                        },
                    )
            },
    )
}

/** The fading controls: a centred play/pause and the seek row just above the viewer's bottom actions. */
@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoViewerPlaybackControls(
    player: Player,
    visible: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(MEDIA_VIEWER_CHROME_FADE_MILLIS)),
        exit = fadeOut(tween(MEDIA_VIEWER_CHROME_FADE_MILLIS)),
    ) {
        Box(Modifier.fillMaxSize().testTag(VIDEO_VIEWER_CONTROLS_TAG)) {
            VideoViewerPlayPauseButton(player, Modifier.align(Alignment.Center))
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = LocalMediaViewerBottomActionsClearance.current)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = ScrimAlpha.HEAVY))
                    .padding(horizontal = 16.dp),
            ) {
                val onChrome = MaterialTheme.colorScheme.onBackground
                VideoSeekSlider(
                    player = player,
                    testTag = VIDEO_VIEWER_SEEK_TAG,
                    showTimes = true,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = onChrome,
                            activeTrackColor = onChrome,
                            inactiveTrackColor = onChrome.copy(alpha = SEEK_INACTIVE_TRACK_ALPHA),
                        ),
                    onScrubbingChange = onScrubbingChange,
                )
            }
        }
    }
}

/** Media3's play/pause rule: Pause while playback is wanted, Play to resume, restart once ended. */
@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoViewerPlayPauseButton(
    player: Player,
    modifier: Modifier = Modifier,
) {
    var showPlay by remember(player) { mutableStateOf(Util.shouldShowPlayButton(player)) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    source: Player,
                    events: Player.Events,
                ) {
                    showPlay = Util.shouldShowPlayButton(source)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    IconButton(
        onClick = { Util.handlePlayPauseButtonAction(player) },
        modifier =
            modifier
                .size(PLAY_PAUSE_TARGET)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE))
                .testTag(VIDEO_VIEWER_PLAY_PAUSE_TAG),
    ) {
        Icon(
            imageVector = if (showPlay) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = stringResource(if (showPlay) R.string.video_play else R.string.video_pause),
            tint = Color.White,
            modifier = Modifier.size(PLAY_PAUSE_ICON),
        )
    }
}

/**
 * Scrub bar without an end-stop marker; dragging holds a local value until the pointer lifts and reports it through
 * [onScrubbingChange]. With [showTimes] it is flanked by elapsed and total time; an unknown duration disables it.
 */
@Composable
@androidx.annotation.OptIn(UnstableApi::class)
internal fun VideoSeekSlider(
    player: Player,
    testTag: String,
    modifier: Modifier = Modifier,
    showTimes: Boolean = false,
    colors: SliderColors = SliderDefaults.colors(),
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var scrub by remember(player) { mutableStateOf<Float?>(null) }
    val latestOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.takeIf { it > 0L } ?: 0L
            position = player.currentPosition.coerceAtLeast(0L)
            delay(VIDEO_PROGRESS_POLL_MILLIS)
        }
    }
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val label = stringResource(R.string.video_playback_position)
    val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = scrub ?: fraction,
            onValueChange = {
                if (scrub == null) latestOnScrubbingChange(true)
                scrub = it
            },
            onValueChangeFinished = {
                scrub?.let { position = (it * duration).toLong().also(player::seekTo) }
                scrub = null
                latestOnScrubbingChange(false)
            },
            enabled = duration > 0L,
            colors = colors,
            modifier =
                sliderModifier
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .testTag(testTag)
                    .heightIn(min = SEEK_MIN_TOUCH_HEIGHT),
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    enabled = duration > 0L,
                    colors = colors,
                    drawStopIndicator = null,
                )
            },
        )
    }
    if (!showTimes) {
        slider(modifier.fillMaxWidth())
        return
    }
    val elapsed = scrub?.let { (it * duration).toLong() } ?: position
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoTimeText(elapsed, VIDEO_VIEWER_ELAPSED_TAG)
        slider(Modifier.weight(1f))
        VideoTimeText(duration.takeIf { it > 0L }, VIDEO_VIEWER_TOTAL_TAG)
    }
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun VideoTimeText(
    millis: Long?,
    testTag: String,
) {
    val text =
        remember(millis) {
            millis?.let { Util.getStringForTime(StringBuilder(), java.util.Formatter(), it) } ?: "--:--"
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * Builds the viewer's Media3 surface. The whole decoded frame fits the page with letterboxing, honouring rotation and
 * pixel aspect ratio. Its own controller is off; [VideoViewerSurface] draws the controls over it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun viewerPlayerView(
    context: Context,
    player: Player,
): PlayerView =
    PlayerView(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        useController = false
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        setBackgroundColor(android.graphics.Color.BLACK)
        this.player = player
    }

/** Keeps playback controls up for at least the user's accessibility "time to take action" setting. */
internal fun viewerControllerTimeoutMs(context: Context): Int =
    context
        .getSystemService(AccessibilityManager::class.java)
        ?.getRecommendedTimeoutMillis(VIDEO_VIEWER_CONTROLS_TIMEOUT_MS, VIDEO_VIEWER_CONTROLS_CONTENT)
        ?: VIDEO_VIEWER_CONTROLS_TIMEOUT_MS
