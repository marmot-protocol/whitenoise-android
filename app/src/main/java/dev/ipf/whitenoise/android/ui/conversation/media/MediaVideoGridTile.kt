package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.automaticAttachmentDownloadSuppressed
import dev.ipf.whitenoise.android.state.readerDownloadParkedForStorage
import dev.ipf.whitenoise.android.state.recordReaderParkedDownload
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single video tile in an album grid. Auto-materialises on first
 * composition (mine + cached short-circuit; otherwise FFI download honoring
 * the auto-download policy), decodes a scaled poster, overlays a centered
 * play affordance. An own video still being sent from its staged file is
 * neither materialized nor opened until MDK confirms it. Tap delegates the logical attachment immediately so a
 * conversation-owned viewer can continue materialization after this lazy tile is disposed.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun MediaVideoGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onTap: () -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    videoFileResolver: VideoViewerFileResolver = ::resolveVideoViewerFile,
    cacheFileProbe: VideoAttachmentCacheProbe = ::cachedVideoAttachmentFile,
) {
    val context = LocalContext.current
    val epoch = reference.sourceEpoch
    val cacheAvailability =
        rememberCachedVideoAttachmentFileState(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            probe = cacheFileProbe,
        )
    var localFile by cacheAvailability.file
    val openingLabel = stringResource(R.string.media_opening)
    // MDK-retained bytes from a background download count as local, so the tile plays offline (#3308).
    val cachedPlaintextOnEntry by
        rememberLocalAttachmentAvailability(controller, messageIdHex, attachmentIndex, epoch)
    val pendingAttachment =
        if (mine) controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex) else null
    // A message still queued for upload has no materialized file to seek in, so
    // its poster comes from the bytes the retry path already retains (#2732).
    val retainedPendingVideoBytes = pendingAttachment?.takeIf { it.isPendingVideo }?.inMemoryBytes
    val retainedPlaintextOnEntry = pendingAttachment?.inMemoryBytes != null
    // A large own video is sent from its staged file, with nothing in memory or published to fetch until it lands.
    val sendingFromStagedFile = pendingAttachment?.sourceFile != null
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageGridTile). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(messageIdHex, attachmentIndex, epoch) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(false) }
    var storageFailed by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(false) }

    /** Fails this attachment, saying "Not enough storage" only when [cause] is a storage park. */
    fun markFailed(cause: Throwable? = null) {
        storageFailed = cause.parksForStorage()
        failed = true
    }
    var tileDurationMs by remember(messageIdHex, attachmentIndex, epoch) { mutableLongStateOf(0L) }
    val tapScope = rememberCoroutineScope()
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    // The reader's Cancel is persisted per attachment, so policy cannot restart what they stopped.
    val cancelledByReader = controller.automaticAttachmentDownloadSuppressed(messageIdHex, attachmentIndex)
    val cacheProbeReady =
        cacheAvailability.resolved.value ||
            localFile != null ||
            cachedPlaintextOnEntry ||
            retainedPlaintextOnEntry ||
            sendingFromStagedFile
    val policyAllowsMaterialization =
        cacheProbeReady &&
            shouldStartVideoAttachmentDownload(
                mine = mine,
                videoAutoDownload = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video) && !cancelledByReader,
                automaticDownloadsPaused = automaticDownloadsPaused,
                hasCachedAttachment = cachedPlaintextOnEntry,
                hasCachedFile = localFile != null,
                hasRetainedPlaintext = retainedPlaintextOnEntry,
                sendingFromStagedFile = sendingFromStagedFile,
            )
    var materializationIntent by
        rememberAttachmentMaterializationIntent(
            identity = "$messageIdHex#$attachmentIndex",
            policyAllowsMaterialization = policyAllowsMaterialization,
        )
    val startDownload = materializationIntent.shouldMaterialize
    restoreParkedDownloadOnEntry(
        identity = Triple(controller, messageIdHex, attachmentIndex),
        enabled = !mine && !startDownload && !cancelledByReader,
        parkedByReader = failed && storageFailed && materializationIntent.readerRequested,
        recordParkedByReader = { controller.recordReaderParkedDownload(messageIdHex, attachmentIndex) },
        readerParkHeld = { controller.readerDownloadParkedForStorage(messageIdHex, attachmentIndex) },
    ) {
        materializationIntent = materializationIntent.afterParkedDownloadFound()
    }
    val transfer =
        rememberTileTransfer(
            controller,
            messageIdHex,
            attachmentIndex,
            reference,
            mine,
            cancelledByReader,
            startDownload,
            failedLocally = failed && localFile == null,
            storageBlockedLocally = storageFailed && failed && localFile == null,
        )
    // The transfer owns the play disc while it is queued, running, being cancelled, cancelled or failed.
    val transferShown = transfer.visible && localFile == null
    var reloadToken by remember(messageIdHex, attachmentIndex, epoch) { mutableIntStateOf(0) }
    storageReadmissionEffect(controller, messageIdHex, attachmentIndex, parked = failed && storageFailed) {
        failed = false
        storageFailed = false
        reloadToken++
    }
    val materializationLock = remember(messageIdHex, attachmentIndex, epoch) { Mutex() }

    /**
     * Opens a clip on the device or downloads one that is not, in place. The explicit Retry control lands here
     * directly, so it retries this tile's download even on the "+N" overflow tile.
     */
    fun downloadOrOpen(retry: Boolean = failed || (localFile == null && transfer.needsDeliberateRetry)) {
        if (transfer.cancelling || sendingFromStagedFile || !cacheProbeReady) return
        val download = {
            // Native failure can offer Retry before the old local waiter reports its own failure.
            if (retry) {
                failed = false
                reloadToken++
            }
            materializationIntent = materializationIntent.afterInteractiveRequest()
            controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
        }
        when {
            // The reader's own clip still held in memory for its upload is on the device whatever its file did.
            retainedPlaintextOnEntry -> onTap()
            localFile != null && !retry ->
                tapScope.launch {
                    val playable = withContext(Dispatchers.IO) { validatedAttachmentCacheFile(localFile) }
                    if (playable != null) {
                        onTap()
                    } else {
                        localFile = null
                        reloadToken++
                        download()
                    }
                }
            retry ->
                controller.retryAttachmentTransfer(
                    messageIdHex,
                    attachmentIndex,
                    onAccepted = { download() },
                    onFailure = { markFailed(it) },
                )
            else -> download()
        }
    }

    /**
     * Handles a tap on the tile. The "+N" overflow always opens the album, whether this tile's own download failed,
     * was cancelled or was evicted. Any other tile opens a clip on the device, downloaded or the reader's own still
     * held in memory for its upload, and only downloads one that is not, in place: it never opens once it arrives,
     * and a later tap opens it. A downloaded file is checked again at tap time, off the main thread, so one the cache
     * evicted while the tile stayed on screen is downloaded again rather than opened. While the reader's Cancel awaits
     * acknowledgement a tap does nothing, so it cannot restart that transfer, and a video still being sent from its
     * staged file has nothing to download yet.
     */
    fun onTileTap() {
        if (transfer.cancelling) return
        // The "+N" overflow is album navigation, so it opens the album whatever this tile's own download did.
        if (overflowCount > 0) onTap() else downloadOrOpen()
    }

    LaunchedEffect(
        messageIdHex,
        attachmentIndex,
        epoch,
        materializationIntent,
        reloadToken,
        cachedPlaintextOnEntry,
    ) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        runCatching {
            // Drain the old shared publication before Retry can join a fresh file load.
            materializationLock.withLock {
                videoFileResolver(
                    context,
                    controller,
                    messageIdHex,
                    attachmentIndex,
                    reference,
                    mine,
                    materializationIntent.priority,
                )
            }
        }.onSuccess { f ->
            currentCoroutineContext().ensureActive()
            localFile = f
            failed = false
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it is kotlinx.coroutines.CancellationException) {
                materializationIntent = materializationIntent.afterProducerCancellation(it)
            } else {
                Log.w("MediaVideoGridTile", "video_tile_auto_materialize_failed")
                markFailed(it)
            }
        }
    }

    persistedAttachmentDownloadEffect(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        sourceEpoch = epoch,
        controller = controller,
        appState = appState,
        isReady = { localFile != null },
        ensureMaterialization = {
            // A video still being sent from its staged file has nothing to materialize until MDK publishes it.
            if (!sendingFromStagedFile) {
                if (failed) {
                    failed = false
                    reloadToken++
                }
                materializationIntent = materializationIntent.afterInteractiveRequest()
            }
        },
    )

    // The retained upload bytes are read in memory, so the pending bubble paints
    // the same poster the confirmed one will and the swap is a visual no-op. The
    // frame is cached under the epoch-independent slot, which is what carries it
    // across upload retry and into the confirmed bubble (#2732).
    LaunchedEffect(messageIdHex, attachmentIndex, retainedPendingVideoBytes) {
        val bytes = retainedPendingVideoBytes ?: return@LaunchedEffect
        if (posterBitmap != null && tileDurationMs > 0L) return@LaunchedEffect
        val frame = withContext(Dispatchers.IO) { pendingVideoPosterFrame(bytes, posterBitmap == null) }
        if (frame.durationMs > 0L) tileDurationMs = frame.durationMs
        val bitmap = frame.bitmap
        if (bitmap != null && posterBitmap == null) {
            controller.cacheThumbnail(messageIdHex, attachmentIndex, bitmap)
            posterBitmap = bitmap.asImageBitmap()
        }
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        if (posterBitmap != null && tileDurationMs > 0L) return@LaunchedEffect
        val needsPoster = posterBitmap == null
        val (frame, duration) =
            withContext(Dispatchers.IO) {
                holdCacheFileInUse(f)
                try {
                    val probed =
                        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder.probe(
                            f,
                            MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            needsPoster,
                        )
                    probed?.poster to (probed?.durationMs ?: 0L)
                } finally {
                    releaseCacheFileInUse(f)
                }
            }
        if (duration > 0L) tileDurationMs = duration
        if (frame != null && posterBitmap == null) {
            // Cache under the epoch-independent slot so a later sourceEpoch
            // upgrade re-seeds the poster instead of flashing the thumbhash.
            controller.cacheThumbnail(messageIdHex, attachmentIndex, frame)
            posterBitmap = frame.asImageBitmap()
        }
    }

    Box(
        modifier =
            modifier
                .testTag(videoAttachmentOpenTestTag(messageIdHex, attachmentIndex))
                .combinedClickable(
                    onLongClick = onLongPress,
                    onClick = { onTileTap() },
                ),
    ) {
        val poster = posterBitmap
        when {
            poster != null ->
                Image(
                    bitmap = poster,
                    contentDescription = stringResource(R.string.reply_media_video),
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize().mediaViewerThumbnail(
                            messageIdHex,
                            attachmentIndex,
                            reference.sourceEpoch,
                            gridTile = true,
                        ),
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
        if (transferShown) {
            TileTransferControl(
                transfer = transfer,
                onRetry = { downloadOrOpen(retry = true) },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!transferShown) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = VIDEO_PLAY_DISC_ALPHA),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = CircleShape,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(VIDEO_PLAY_DISC_SIZE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        // Never fetched while it sends, so it shows the play glyph of a pending video held in memory.
                        sendingFromStagedFile ->
                            Icon(
                                painter = painterResource(R.drawable.ic_play_arrow),
                                contentDescription = stringResource(R.string.reply_media_video),
                                modifier = Modifier.size(24.dp),
                            )
                        failed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.video_failed),
                                modifier = Modifier.size(24.dp),
                            )
                        !cacheProbeReady ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).semantics { contentDescription = openingLabel },
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.media_tap_to_download),
                                modifier = Modifier.size(24.dp),
                            )
                        localFile == null ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        else ->
                            Icon(
                                painter = painterResource(R.drawable.ic_play_arrow),
                                contentDescription = stringResource(R.string.reply_media_video),
                                modifier = Modifier.size(24.dp),
                            )
                    }
                }
            }
        }
        // The prototype's duration chip sits bottom-end, once the duration is known.
        if (tileDurationMs > 0L) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = VIDEO_DURATION_CHIP_ALPHA),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(VIDEO_DURATION_CHIP_INSET),
            ) {
                Text(
                    formatVoiceTime(tileDurationMs.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        // An unplayable clip is dimmed and badged rather than left looking merely slow. While the transfer control owns
        // the centre its Retry glyph is the failure cue, so the badge only dims the tile instead of covering it.
        if (failed) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = VIDEO_UNAVAILABLE_SCRIM_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                if (!transferShown) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = stringResource(R.string.video_failed),
                        modifier = Modifier.size(VIDEO_UNAVAILABLE_GLYPH),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
        if (overflowCount > 0) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = ALBUM_OVERFLOW_SCRIM_ALPHA)),
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

private val VIDEO_DURATION_CHIP_INSET = 6.dp

/** Album overflow tiles share one dim across photos and videos. */
private const val ALBUM_OVERFLOW_SCRIM_ALPHA = 0.58f

/** An unavailable clip keeps its poster readable under a light dim and a warning glyph. */
private const val VIDEO_UNAVAILABLE_SCRIM_ALPHA = 0.28f
private val VIDEO_UNAVAILABLE_GLYPH = 28.dp
