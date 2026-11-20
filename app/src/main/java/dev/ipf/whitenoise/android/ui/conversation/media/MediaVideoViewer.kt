package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.playbackErrorInvalidatesAttachmentCache
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val videoPlaybackAudioAttributes =
    androidx.media3.common.AudioAttributes
        .Builder()
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .build()

/**
 * One page of the full-screen pager. Owns its own download + decode + pan/zoom
 * state so swiping to a sibling page doesn't carry zoom across, and disposing
 * the page recycles the multi-MB native bitmap instead of leaning on GC. The
 * pager precomposes one page either side by default, so transfer and playback
 * are gated on [isCurrent] to avoid concurrent downloads and decoders. Lifecycle STOP
 * and noisy audio routes pause playback; pending or replaced files retain pause intent so
 * foreground return cannot start audio without a new page selection or an explicit Play action.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("FunctionNaming", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
@Composable
internal fun VideoViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    isCurrent: Boolean,
    mine: Boolean,
    onPlayerChanged: (androidx.media3.common.Player?) -> Unit = {},
    videoFileResolver: VideoViewerFileResolver = ::resolveVideoViewerFile,
    controlsVisible: Boolean = true,
    onControlsVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestReference by rememberUpdatedState(reference)
    val latestOnPlayerChanged by rememberUpdatedState(onPlayerChanged)
    val latestOnControlsVisibleChange by rememberUpdatedState(onControlsVisibleChange)
    val contentKey = videoAttachmentContentKey(messageIdHex, attachmentIndex, reference)
    // Remember STOP even while the file is pending and background recomposition is suspended.
    // A deliberate new page/open gets fresh admission; foreground return alone never rearms it.
    val autoplayAllowed =
        remember(contentKey, isCurrent, lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    DisposableEffect(autoplayAllowed, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) autoplayAllowed.value = false
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val playbackRecoveryJob =
        remember(contentKey) {
            mutableStateOf<Job?>(null)
        }
    DisposableEffect(playbackRecoveryJob) {
        onDispose { playbackRecoveryJob.value?.cancel() }
    }
    var localFile by
        rememberCachedVideoAttachmentFileState(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        ).file
    var playbackInvalidated by remember(contentKey) {
        mutableStateOf(false)
    }
    var cacheInvalidating by remember(contentKey) {
        mutableStateOf(false)
    }
    var loadFailed by remember(contentKey) { mutableStateOf(false) }
    var reloadToken by remember(contentKey) { mutableIntStateOf(0) }
    LaunchedEffect(contentKey, reference.sourceEpoch, isCurrent, reloadToken, localFile) {
        if (!isCurrent) return@LaunchedEffect
        if (localFile != null) return@LaunchedEffect
        if (playbackInvalidated) return@LaunchedEffect
        loadFailed = false
        runCatching {
            videoFileResolver(
                context,
                controller,
                messageIdHex,
                attachmentIndex,
                reference,
                mine,
                AttachmentDownloadPriority.Interactive,
            )
        }.onSuccess { localFile = it }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w("VideoViewerPage", "video_materialize_failed")
                loadFailed = true
            }
    }
    val file = localFile
    if (file == null) {
        // Without a player there are no controls to reveal hidden chrome, so a page that loads or loses its
        // player restores the chrome rather than stranding Close behind a retry state.
        LaunchedEffect(contentKey) { latestOnControlsVisibleChange(true) }
        VideoViewerUnavailable(
            cacheInvalidating = cacheInvalidating,
            playbackInvalidated = playbackInvalidated,
            loadFailed = loadFailed,
            onPlaybackRetry = {
                controller.retryAttachmentTransfer(
                    messageIdHex,
                    attachmentIndex,
                    onAccepted = {
                        cacheInvalidating = true
                        playbackRecoveryJob.value =
                            scope.launch {
                                try {
                                    localFile =
                                        rematerializeVideoAttachmentAfterPlaybackFailure(
                                            context = context,
                                            controller = controller,
                                            messageIdHex = messageIdHex,
                                            attachmentIndex = attachmentIndex,
                                            reference = reference,
                                            mine = mine,
                                        )
                                    playbackInvalidated = false
                                } catch (t: Throwable) {
                                    currentCoroutineContext().ensureActive()
                                    if (t is CancellationException) throw t
                                    playbackInvalidated = true
                                } finally {
                                    cacheInvalidating = false
                                }
                            }
                    },
                    onFailure = {
                        playbackInvalidated = true
                        cacheInvalidating = false
                    },
                )
            },
            onLoadRetry = {
                controller.retryAttachmentTransfer(
                    messageIdHex,
                    attachmentIndex,
                    onAccepted = {
                        loadFailed = false
                        reloadToken++
                    },
                    onFailure = { loadFailed = true },
                )
            },
        )
        return
    }
    val playerKey = videoViewerPlayerKey(file, messageIdHex, attachmentIndex, reference)
    val exo =
        remember(playerKey) {
            requireNotNull(localVideoPlayer(context, android.net.Uri.fromFile(file)))
                .apply {
                    setAudioAttributes(videoPlaybackAudioAttributes, true)
                    setHandleAudioBecomingNoisy(true)
                    addListener(
                        object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                if (!playbackErrorInvalidatesAttachmentCache(error)) return
                                if (playbackInvalidated || cacheInvalidating) return
                                playbackInvalidated = true
                                cacheInvalidating = true
                                playbackRecoveryJob.value =
                                    scope.launch {
                                        try {
                                            clearVideoAttachmentCacheAfterPlaybackFailure(
                                                context = context,
                                                controller = controller,
                                                messageIdHex = messageIdHex,
                                                attachmentIndex = attachmentIndex,
                                                reference = latestReference,
                                                mine = mine,
                                            )
                                            localFile = null
                                        } catch (t: Throwable) {
                                            if (t is CancellationException) throw t
                                            localFile = null
                                        } finally {
                                            cacheInvalidating = false
                                        }
                                    }
                            }
                        },
                    )
                }
        }
    DisposableEffect(exo) {
        latestOnPlayerChanged(exo)
        onDispose {
            latestOnPlayerChanged(null)
            exo.release()
        }
    }
    // Pause the mounted player on Home or screen lock. START intentionally does not resume it:
    // only the user's play action may resume after a stop or a disconnected audio route.
    DisposableEffect(exo, lifecycle, autoplayAllowed) {
        val pauseListener =
            object : androidx.media3.common.Player.Listener {
                /** Carries a pause or subsequent explicit Play across replacement of the same attachment's file. */
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    autoplayAllowed.value = playWhenReady
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) exo.pause()
            }
        exo.addListener(pauseListener)
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) exo.pause()
        onDispose {
            exo.removeListener(pauseListener)
            lifecycle.removeObserver(observer)
        }
    }
    // The player reopens its file to seek or replay, so the cache holds it for as long as this page plays it.
    HoldCacheFileWhileComposed(file)
    // Only the current page owns a decoder or audio focus. HorizontalPager
    // pre-composes neighbours, so preparing in remember would hold multiple
    // MediaCodec instances and could invalidate healthy cache entries when a
    // neighbour fails decoder acquisition.
    LaunchedEffect(isCurrent, exo) {
        if (isCurrent) {
            VoicePlaybackController.pause()
            // Materialization may finish after STOP, before this player has observed any lifecycle event.
            // Publish current autoplay intent before preparation so explicit video avoids an optional poster probe.
            exo.playWhenReady = autoplayAllowed.value && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            exo.prepare()
        } else {
            exo.playWhenReady = false
            exo.stop()
        }
    }
    VideoViewerSurface(
        player = exo,
        controlsVisible = controlsVisible,
        onControlsVisibleChange = latestOnControlsVisibleChange,
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

/** Placeholder when a video cannot play: invalidated cache, invalidated playback or load failure. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun VideoViewerUnavailable(
    cacheInvalidating: Boolean,
    playbackInvalidated: Boolean,
    loadFailed: Boolean,
    onPlaybackRetry: () -> Unit,
    onLoadRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            cacheInvalidating -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            playbackInvalidated ->
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.video_failed),
                    tint = Color.White,
                    modifier = Modifier.size(48.dp).clickable(onClick = onPlaybackRetry),
                )
            loadFailed -> MediaViewerLoadFailed(onRetry = onLoadRetry, contentColor = Color.White)
            else -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}
