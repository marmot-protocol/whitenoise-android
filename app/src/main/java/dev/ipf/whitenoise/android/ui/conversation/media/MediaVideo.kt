package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.playbackErrorInvalidatesAttachmentCache
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private val videoMaterializations = SingleFlight<String, java.io.File>()

private val videoPlaybackAudioAttributes =
    androidx.media3.common.AudioAttributes
        .Builder()
        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
        .build()

/**
 * Single video tile in an album grid. Auto-materialises on first
 * composition (mine + cached short-circuit; otherwise FFI download honoring
 * the auto-download policy), decodes a scaled poster, overlays a centered
 * play affordance. Tap delivers the materialised file to the parent so
 * the bubble can open the fullscreen player.
 */
@Composable
internal fun MediaVideoGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onTap: (java.io.File) -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val epoch = reference.sourceEpoch
    var localFile by
        rememberCachedVideoAttachmentFileState(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val cachedPlaintextOnEntry =
        remember(messageIdHex, attachmentIndex) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageGridTile). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(messageIdHex, attachmentIndex, epoch) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    var startDownload by remember(
        messageIdHex,
        attachmentIndex,
        appState.mediaAutoDownloadMatrix,
        automaticDownloadsPaused,
    ) {
        mutableStateOf(
            shouldStartVideoAttachmentDownload(
                mine = mine,
                videoAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video),
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = localFile != null,
            ),
        )
    }
    var interactiveDownloadRequested by remember(messageIdHex, attachmentIndex) { mutableStateOf(false) }
    var reloadToken by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(0) }

    LaunchedEffect(
        messageIdHex,
        attachmentIndex,
        epoch,
        startDownload,
        reloadToken,
        cachedPlaintextOnEntry,
        interactiveDownloadRequested,
    ) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Re-probe the decrypted-byte cache right before using the epoch-0 bypass;
        // the remembered entry snapshot only decides initial UI/download policy.
        if (
            !mine &&
            epoch == 0uL &&
            !controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        ) {
            return@LaunchedEffect
        }
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
                priority =
                    if (interactiveDownloadRequested) {
                        AttachmentDownloadPriority.Interactive
                    } else {
                        AttachmentDownloadPriority.Automatic
                    },
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            failed = true
        }
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        if (posterBitmap != null) return@LaunchedEffect
        val frame =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                    mmr.getScaledFrameAtTime(
                        0L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        edge,
                        edge,
                    )
                } catch (t: Throwable) {
                    null
                } finally {
                    runCatching { mmr.release() }
                }
            }
        if (frame != null) {
            // Cache under the epoch-independent slot so a later sourceEpoch
            // upgrade re-seeds the poster instead of flashing the thumbhash.
            controller.cacheThumbnail(messageIdHex, attachmentIndex, frame)
            posterBitmap = frame.asImageBitmap()
        }
    }

    Box(
        modifier =
            modifier.combinedClickable(
                onLongClick = onLongPress,
                onClick = {
                    val f = localFile
                    when {
                        f != null ->
                            scope.launch {
                                val playableFile =
                                    withContext(Dispatchers.IO) {
                                        validatedAttachmentCacheFile(f)
                                    } ?: runCatching {
                                        materializeVideoAttachment(
                                            context = context,
                                            controller = controller,
                                            messageIdHex = messageIdHex,
                                            attachmentIndex = attachmentIndex,
                                            reference = reference,
                                            mine = mine,
                                        )
                                    }.getOrElse { error ->
                                        if (error is CancellationException) throw error
                                        localFile = null
                                        failed = true
                                        return@launch
                                    }
                                localFile = playableFile
                                failed = false
                                onTap(playableFile)
                            }
                        failed -> {
                            interactiveDownloadRequested = true
                            failed = false
                            reloadToken++
                        }
                        else -> {
                            interactiveDownloadRequested = true
                            startDownload = true
                        }
                    }
                },
            ),
    ) {
        val poster = posterBitmap
        when {
            poster != null ->
                Image(
                    bitmap = poster,
                    contentDescription = stringResource(R.string.reply_media_video),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            thumbhashImage != null ->
                Image(
                    bitmap = thumbhashImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            else ->
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Surface(
            color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE),
            shape = CircleShape,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    failed ->
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.voice_message_failed),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    !startDownload && localFile == null ->
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.media_open),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    localFile == null ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    else ->
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.reply_media_video),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                }
            }
        }
        if (overflowCount > 0) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = ScrimAlpha.CONTROLS)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = ScrimAlpha.FAINT)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun MediaVideoBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    uploadFailed: Boolean = false,
    onRetryUpload: (() -> Unit)? = null,
    attachedToCaption: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    val epoch = reference.sourceEpoch
    val cachedThumbnail =
        remember(pillKey) {
            controller.thumbnailFor(messageIdHex, attachmentIndex)
        }
    val bubbleAspectRatio =
        rememberMediaBubbleAspectRatio(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            dim = reference.dim,
        )
    val playbackRecoveryJob =
        remember(pillKey, epoch, reference.mediaType) {
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
        )
    val cachedPlaintextOnEntry =
        remember(pillKey) {
            controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        }
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageBubble). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(pillKey, epoch) {
        mutableStateOf(cachedThumbnail?.asImageBitmap())
    }
    var durationMs by remember(pillKey, epoch) { mutableStateOf(0L) }
    var playerOpen by remember(pillKey) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    // Mirrors the image bubble's auto-download gate, but already-local bytes
    // bypass the network-spend policy so chat re-entry starts at Play instead
    // of showing a fake Download affordance. When the policy says no for an
    // uncached video (e.g. Wi-Fi-only on cellular), a tap flips
    // startDownload=true so the user always has a path to fetch — never
    // "looks present but can't be opened". See PR #191 reviewer feedback.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    var startDownload by remember(
        pillKey,
        appState.mediaAutoDownloadMatrix,
        automaticDownloadsPaused,
    ) {
        mutableStateOf(
            shouldStartVideoAttachmentDownload(
                mine = mine,
                videoAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video),
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = localFile != null,
            ),
        )
    }
    var interactiveDownloadRequested by remember(pillKey) { mutableStateOf(false) }

    LaunchedEffect(
        pillKey,
        epoch,
        startDownload,
        cachedPlaintextOnEntry,
        interactiveDownloadRequested,
    ) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Re-probe the decrypted-byte cache right before using the epoch-0 bypass;
        // the remembered entry snapshot only decides initial UI/download policy.
        if (
            !mine &&
            epoch == 0uL &&
            !controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        ) {
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
                priority =
                    if (interactiveDownloadRequested) {
                        AttachmentDownloadPriority.Interactive
                    } else {
                        AttachmentDownloadPriority.Automatic
                    },
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVideoBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        // Poster may already be seeded from cache after a sourceEpoch upgrade;
        // still read duration so the label survives, but do not decode another frame.
        if (posterBitmap != null && durationMs > 0L) return@LaunchedEffect
        val needsPoster = posterBitmap == null
        val (bm, dur) =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val frame =
                        if (needsPoster) {
                            // Scale down to bubble preview size so a 4K source doesn't
                            // hold a ~33 MB ARGB bitmap per visible video bubble.
                            val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                            mmr.getScaledFrameAtTime(
                                0L,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                edge,
                                edge,
                            )
                        } else {
                            null
                        }
                    val d =
                        mmr
                            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    frame to d
                } catch (t: Throwable) {
                    Log.w("MediaVideoBubble", "poster extract failed", t)
                    null to 0L
                } finally {
                    runCatching { mmr.release() }
                }
            }
        if (dur > 0L) durationMs = dur
        if (bm != null && posterBitmap == null) {
            // Cache under the epoch-independent slot so a later sourceEpoch
            // upgrade re-seeds the poster instead of flashing the thumbhash.
            controller.cacheThumbnail(messageIdHex, attachmentIndex, bm)
            posterBitmap = bm.asImageBitmap()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = visualMediaBubbleShape(attachedToCaption),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier = imageBubbleSizing(bubbleAspectRatio),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val poster = posterBitmap
            when {
                poster != null ->
                    Image(
                        bitmap = poster,
                        contentDescription = stringResource(R.string.reply_media_video),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                thumbhashImage != null ->
                    Image(
                        bitmap = thumbhashImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }

            // Centered play overlay — semi-transparent dark circle with white
            // triangle. While uploading we replace the triangle with a spinner
            // so the user sees the send is in flight (matches the image bubble).
            // When startDownload is gated off (policy says no auto-fetch), the
            // triangle becomes a download icon and tap consents to the fetch.
            Surface(
                color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE),
                shape = CircleShape,
                modifier =
                    Modifier
                        .size(56.dp)
                        .combinedClickable(
                            onLongClick = onLongPress,
                            onClick = {
                                when {
                                    uploadFailed -> onRetryUpload?.invoke()
                                    loading -> interactiveDownloadRequested = true
                                    else ->
                                        scope.launch {
                                            interactiveDownloadRequested = true
                                            val retainedFile =
                                                withContext(Dispatchers.IO) {
                                                    validatedAttachmentCacheFile(localFile)
                                                }
                                            if (retainedFile != null && !failed) {
                                                playerOpen = true
                                                return@launch
                                            }
                                            if (localFile != null) {
                                                localFile = null
                                                posterBitmap = null
                                                durationMs = 0L
                                            }
                                            startDownload = true
                                            loading = true
                                            runCatching {
                                                materializeVideoAttachment(
                                                    context = context,
                                                    controller = controller,
                                                    messageIdHex = messageIdHex,
                                                    attachmentIndex = attachmentIndex,
                                                    reference = reference,
                                                    mine = mine,
                                                )
                                            }.onSuccess { file ->
                                                localFile = file
                                                failed = false
                                                playerOpen = true
                                            }.onFailure {
                                                if (it is CancellationException) throw it
                                                Log.w(
                                                    "MediaVideoBubble",
                                                    "manual materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex",
                                                    it,
                                                )
                                                failed = true
                                            }
                                            loading = false
                                        }
                                }
                            },
                        ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        uploadFailed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable { onRetryUpload?.invoke() },
                            )
                        uploading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.media_open),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        loading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        failed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable(enabled = !loading) {
                                            loading = true
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
                                                        failed = false
                                                    } catch (t: Throwable) {
                                                        if (t is CancellationException) throw t
                                                        failed = true
                                                        localFile = null
                                                    } finally {
                                                        loading = false
                                                    }
                                                }
                                        },
                            )
                        else ->
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.reply_media_video),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                    }
                }
            }

            // Duration pill bottom-start. Only shown once duration is known.
            if (durationMs > 0L) {
                Surface(
                    color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE),
                    shape = RoundedCornerShape(6.dp),
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                ) {
                    Text(
                        formatVoiceTime(durationMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    if (playerOpen) {
        val file = localFile
        if (file != null) {
            FullscreenVideoPlayer(
                file = file,
                onDismiss = { playerOpen = false },
                onPlaybackError = {
                    if (loading || failed) return@FullscreenVideoPlayer
                    failed = true
                    playerOpen = false
                    loading = true
                    playbackRecoveryJob.value =
                        scope.launch {
                            try {
                                clearVideoAttachmentCacheAfterPlaybackFailure(
                                    context = context,
                                    controller = controller,
                                    messageIdHex = messageIdHex,
                                    attachmentIndex = attachmentIndex,
                                    reference = reference,
                                )
                                localFile = null
                                posterBitmap = null
                                durationMs = 0L
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                localFile = null
                            } finally {
                                loading = false
                            }
                        }
                },
            )
        }
    }
}

/** Decrypted video on disk under cacheDir/video_attachments; reuses the
 *  age-based janitor that already sweeps shared_media / voice_attachments. */
@VisibleForTesting
internal suspend fun materializeVideoAttachment(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val file =
        videoAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        )
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )

    return videoMaterializations.run(file.absolutePath) {
        materializeVideoAttachmentOnce(attachmentKey, file, resolveBytes)
    }
}

internal suspend fun materializeVideoAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
): java.io.File =
    materializeVideoAttachment(
        context = context,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        resolveBytes = {
            val retained =
                if (mine) {
                    controller
                        .pendingAttachmentsList(messageIdHex)
                        .getOrNull(attachmentIndex)
                        ?.plaintextBytes
                } else {
                    null
                }
            retained ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference, priority)
        },
    )

private suspend fun materializeVideoAttachmentOnce(
    attachmentKey: String,
    file: java.io.File,
    resolveBytes: suspend () -> ByteArray,
): java.io.File {
    val cachedFile =
        withContext(Dispatchers.IO) {
            file
                .takeIf { it.isFile && it.length() > 0L }
                ?.also(AttachmentPlaintextCache::touch)
        }
    if (cachedFile != null) return cachedFile
    val published =
        AttachmentCachePublication.publishAfterLoad(
            attachmentKey = attachmentKey,
            finalFile = file,
            loadBytes = resolveBytes,
        )
    if (!published) {
        throw IOException("attachment cache publication aborted for ${file.name}")
    }
    return file
}

@VisibleForTesting
internal suspend fun invalidateVideoAttachmentCacheAfterPlaybackFailure(
    attachmentKey: String,
    file: java.io.File,
    evictPlaintext: suspend () -> Unit,
) {
    withContext(NonCancellable) {
        AttachmentCachePublication.invalidateAttachmentCache(
            attachmentKey = attachmentKey,
            finalFile = file,
            evictPlaintext = evictPlaintext,
        )
    }
}

private suspend fun clearVideoAttachmentCacheAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
) {
    val attachmentKey =
        AttachmentCachePublication.attachmentKey(
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceEpoch = reference.sourceEpoch,
        )
    invalidateVideoAttachmentCacheAfterPlaybackFailure(
        attachmentKey = attachmentKey,
        file =
            videoAttachmentCacheFile(
                context = context,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
            ),
    ) {
        controller.evictCachedAttachment(messageIdHex, attachmentIndex)
    }
}

private suspend fun rematerializeVideoAttachmentAfterPlaybackFailure(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    clearVideoAttachmentCacheAfterPlaybackFailure(
        context = context,
        controller = controller,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
    )
    return materializeVideoAttachment(
        context = context,
        controller = controller,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        mine = mine,
    )
}

@VisibleForTesting
internal fun cachedVideoAttachmentFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File? =
    validatedAttachmentCacheFile(
        videoAttachmentCacheFile(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
        ),
    )

@Composable
private fun rememberCachedVideoAttachmentFileState(
    context: Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): MutableState<java.io.File?> {
    val cachedFile =
        remember(messageIdHex, attachmentIndex, reference.sourceEpoch, reference.mediaType) {
            mutableStateOf<java.io.File?>(null)
        }
    LaunchedEffect(messageIdHex, attachmentIndex, reference.sourceEpoch, reference.mediaType) {
        val file =
            withContext(Dispatchers.IO) {
                cachedVideoAttachmentFile(
                    context = context,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                )
            }
        if (cachedFile.value == null) cachedFile.value = file
    }
    return cachedFile
}

@VisibleForTesting
internal fun shouldStartVideoAttachmentDownload(
    mine: Boolean,
    videoAutoDownload: Boolean,
    hasCachedAttachment: Boolean,
    hasCachedFile: Boolean,
): Boolean = mine || videoAutoDownload || hasCachedAttachment || hasCachedFile

private fun videoAttachmentCacheFile(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File {
    val dir = java.io.File(context.cacheDir, MediaCacheDirs.VIDEO)
    return java.io.File(
        dir,
        "$messageIdHex-$attachmentIndex-${reference.sourceEpoch}.${videoAttachmentExtension(reference)}",
    )
}

@VisibleForTesting
internal fun videoAttachmentCacheFileForTests(
    context: android.content.Context,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): java.io.File = videoAttachmentCacheFile(context, messageIdHex, attachmentIndex, reference)

private fun videoAttachmentExtension(reference: MediaAttachmentReferenceFfi): String =
    when {
        reference.mediaType.contains("quicktime", ignoreCase = true) -> "mov"
        reference.mediaType.contains("webm", ignoreCase = true) -> "webm"
        else -> "mp4"
    }

/**
 * Fullscreen player backed by Media3 ExoPlayer + PlayerView — the same
 * controller the platform media apps ship. Tap toggles the transport bar;
 * play/pause/seek work reliably without VideoView's MediaController quirks.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(
    file: java.io.File,
    onDismiss: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val exo =
        remember(file) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setAudioAttributes(videoPlaybackAudioAttributes, true)
                    addListener(
                        object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                if (playbackErrorInvalidatesAttachmentCache(error)) {
                                    currentOnPlaybackError()
                                }
                            }
                        },
                    )
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    LaunchedEffect(exo) {
        VoicePlaybackController.pause()
        exo.prepare()
        exo.playWhenReady = true
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = 2500
                    }
                },
                onRelease = { playerView -> playerView.player = null },
            )
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * One page of the full-screen pager. Owns its own download + decode + pan/zoom
 * state so swiping to a sibling page doesn't carry zoom across, and disposing
 * the page recycles the multi-MB native bitmap instead of leaning on GC. The
 * pager prefetches one page either side by default, which is why
 * `LaunchedEffect` doesn't need to wait for "page becomes visible" — it
 * downloads as soon as the page composes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    isCurrent: Boolean,
    mine: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbackRecoveryJob =
        remember(
            messageIdHex,
            attachmentIndex,
            reference.sourceEpoch,
            reference.mediaType,
        ) {
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
        )
    var playbackInvalidated by remember(
        messageIdHex,
        attachmentIndex,
        reference.sourceEpoch,
        reference.mediaType,
    ) {
        mutableStateOf(false)
    }
    var cacheInvalidating by remember(
        messageIdHex,
        attachmentIndex,
        reference.sourceEpoch,
        reference.mediaType,
    ) {
        mutableStateOf(false)
    }
    LaunchedEffect(messageIdHex, attachmentIndex, reference.sourceEpoch) {
        if (localFile != null) return@LaunchedEffect
        if (playbackInvalidated) return@LaunchedEffect
        // Receive-side: skip epoch=0 (FFI download would error). Own
        // optimistic sends still have their bytes in pendingAttachmentsList
        // even at epoch=0, so we let materializeVideoAttachment short-
        // circuit through the retained-bytes path with mine=true.
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { localFile = it }
    }
    val file = localFile
    if (file == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                cacheInvalidating ->
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                playbackInvalidated ->
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.voice_message_failed),
                        tint = Color.White,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clickable(enabled = !cacheInvalidating) {
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
                                                if (t is CancellationException) throw t
                                                playbackInvalidated = true
                                            } finally {
                                                cacheInvalidating = false
                                            }
                                        }
                                },
                    )
                else ->
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        }
        return
    }
    val exo =
        remember(
            file,
            messageIdHex,
            attachmentIndex,
            reference.sourceEpoch,
            reference.mediaType,
        ) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setAudioAttributes(videoPlaybackAudioAttributes, true)
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
                                                reference = reference,
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
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    // Only the current page owns a decoder or audio focus. HorizontalPager
    // pre-composes neighbours, so preparing in remember would hold multiple
    // MediaCodec instances and could invalidate healthy cache entries when a
    // neighbour fails decoder acquisition.
    LaunchedEffect(isCurrent, exo) {
        if (isCurrent) {
            VoicePlaybackController.pause()
            exo.prepare()
            exo.playWhenReady = true
        } else {
            exo.playWhenReady = false
            exo.stop()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exo
                useController = true
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                controllerShowTimeoutMs = 2500
            }
        },
        onRelease = { playerView -> playerView.player = null },
    )
}
