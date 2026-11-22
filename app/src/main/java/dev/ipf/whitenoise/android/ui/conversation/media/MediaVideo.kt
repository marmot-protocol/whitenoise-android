package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
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
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FileUploadProgress
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.automaticAttachmentDownloadSuppressed
import dev.ipf.whitenoise.android.state.readerDownloadParkedForStorage
import dev.ipf.whitenoise.android.state.recordReaderParkedDownload
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stable semantics identity for a visual-video affordance in production Compose tests. */
internal fun videoAttachmentOpenTestTag(
    messageIdHex: String,
    attachmentIndex: Int,
): String = "video-attachment-open:$messageIdHex:$attachmentIndex"

/**
 * Cryptographic identity shared by viewer state that must survive metadata-only
 * source-epoch upgrades but reset before different bytes can reuse it.
 */
internal data class VideoAttachmentContentKey(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val mediaType: String,
    val plaintextSha256: String,
    val ciphertextSha256: String,
    val nonceHex: String,
    val version: EncryptedMediaVersionFfi,
)

/** Builds the source-epoch-independent identity for one encrypted video attachment. */
internal fun videoAttachmentContentKey(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): VideoAttachmentContentKey =
    VideoAttachmentContentKey(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        mediaType = reference.mediaType,
        plaintextSha256 = reference.plaintextSha256,
        ciphertextSha256 = reference.ciphertextSha256,
        nonceHex = reference.nonceHex,
        version = reference.version,
    )

/** Exact materialized-content identity that owns one Media3 player instance. */
internal data class VideoViewerPlayerKey(
    val contentKey: VideoAttachmentContentKey,
    val filePath: String,
)

/** Keeps a player through metadata-only source-epoch upgrades and re-keys for a different file. */
internal fun videoViewerPlayerKey(
    file: java.io.File,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): VideoViewerPlayerKey =
    VideoViewerPlayerKey(
        contentKey = videoAttachmentContentKey(messageIdHex, attachmentIndex, reference),
        filePath = file.absolutePath,
    )

/** Injectable owner-chain seam for deterministic device coverage of pending materialization. */
internal typealias VideoViewerFileResolver =
    suspend (
        context: Context,
        controller: ConversationController,
        messageIdHex: String,
        attachmentIndex: Int,
        reference: MediaAttachmentReferenceFfi,
        mine: Boolean,
        priority: AttachmentDownloadPriority,
    ) -> java.io.File

/** Resolves a viewer's video through the production controller-owned materialization path. */
internal suspend fun resolveVideoViewerFile(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    priority: AttachmentDownloadPriority,
): java.io.File =
    materializeVideoAttachment(
        context = context,
        controller = controller,
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference,
        mine = mine,
        priority = priority,
    )

/**
 * Materializes one inline video and delegates its open request to the conversation host. While a large
 * video is being sent, [uploadProgress] is read by the play disc's ring, which fills with the send's progress.
 */
@Composable
internal fun MediaVideoBubble(
    item: TimelineMessage,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onOpenConversationMedia: (ConversationMediaViewerOpenRequest) -> Unit,
    mine: Boolean,
    modifier: Modifier = Modifier,
    videoFileResolver: VideoViewerFileResolver = ::resolveVideoViewerFile,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    uploadFailed: Boolean = false,
    onRetryUpload: (() -> Unit)? = null,
    uploadProgress: () -> FileUploadProgress? = { null },
    cacheFileProbe: VideoAttachmentCacheProbe = ::cachedVideoAttachmentFile,
) {
    val record = item.record
    val messageIdHex = record.messageIdHex
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
    // MDK-retained bytes from a background download count as local, so the pill plays offline (#3308).
    val cachedPlaintextOnEntry by
        rememberLocalAttachmentAvailability(controller, messageIdHex, attachmentIndex, Pair(pillKey, epoch))
    val pendingAttachment =
        if (mine) controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex) else null
    // A message still queued for upload has no materialized file to seek in, so
    // its poster comes from the bytes the retry path already retains (#2732).
    val retainedPendingVideoBytes = pendingAttachment?.takeIf { it.isPendingVideo }?.inMemoryBytes
    val retainedPlaintextOnEntry = pendingAttachment?.inMemoryBytes != null
    // A large own video is sent from its staged file, with nothing in memory or published to fetch until it lands.
    val sendingFromStagedFile = pendingAttachment?.isFileBacked == true
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    var storageFailed by remember(pillKey, epoch) { mutableStateOf(false) }

    /** Fails this attachment, saying "Not enough storage" only when [cause] is a storage park. */
    fun markFailed(cause: Throwable? = null) {
        storageFailed = cause.parksForStorage()
        failed = true
    }
    // Seed the poster from the epoch-independent thumbnail cache (mirrors
    // MediaImageBubble). A sourceEpoch upgrade re-keys this state, so without
    // the cache seed the poster would reset to null and flash back to the
    // thumbhash before the frame is re-extracted, even though the video is
    // already downloaded.
    var posterBitmap by remember(pillKey, epoch) {
        mutableStateOf(cachedThumbnail?.asImageBitmap())
    }
    var durationMs by remember(pillKey, epoch) { mutableLongStateOf(0L) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    // Mirrors the image bubble's auto-download gate, but already-local bytes
    // bypass the network-spend policy so chat re-entry starts at Play instead
    // of showing a fake Download affordance. When the policy says no for an
    // uncached video (e.g. Wi-Fi-only on cellular), a tap promotes the
    // materialization intent so the user always has a path to fetch — never
    // "looks present but can't be opened". See PR #191 reviewer feedback.
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
            identity = pillKey,
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
            failedLocally = failed && localFile == null && !loading,
            storageBlockedLocally = storageFailed && failed && localFile == null && !loading,
        )
    // The transfer owns the play disc while it is queued, running, being cancelled, cancelled or failed.
    val transferShown = transfer.visible && localFile == null

    /** Re-materializes a clip whose playback failed once the engine accepts a fresh Retry. */
    fun retryFailedPlayback() {
        controller.retryAttachmentTransfer(
            messageIdHex,
            attachmentIndex,
            onAccepted = {
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
                            currentCoroutineContext().ensureActive()
                            if (t is CancellationException) throw t
                            markFailed(t)
                            localFile = null
                        } finally {
                            loading = false
                        }
                    }
            },
            onFailure = { markFailed(it) },
        )
    }
    var reloadToken by remember(pillKey, epoch) { mutableIntStateOf(0) }
    storageReadmissionEffect(controller, messageIdHex, attachmentIndex, parked = failed && storageFailed) {
        failed = false
        storageFailed = false
        reloadToken++
    }
    val materializationLock = remember(pillKey, epoch) { Mutex() }

    LaunchedEffect(
        pillKey,
        epoch,
        materializationIntent,
        cachedPlaintextOnEntry,
        reloadToken,
    ) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        loading = true
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
                Log.w("MediaVideoBubble", "video_auto_materialize_failed")
                markFailed(it)
            }
        }
        loading = false
    }

    /**
     * Downloads a clip that is not on the device yet, in place. It never opens once it arrives; a later tap opens it.
     * A video still being sent from its staged file has nothing to download yet.
     */
    fun requestDownload(retry: Boolean = failed || (localFile == null && transfer.needsDeliberateRetry)) {
        if (!cacheProbeReady) return
        if (transfer.cancelling || sendingFromStagedFile) return
        val download = {
            // Native failure can offer Retry before the old local waiter reports its own failure.
            if (retry) {
                failed = false
                reloadToken++
            }
            materializationIntent = materializationIntent.afterInteractiveRequest()
            controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
        }
        if (retry) {
            controller.retryAttachmentTransfer(
                messageIdHex,
                attachmentIndex,
                onAccepted = { download() },
                onFailure = { markFailed(it) },
            )
        } else {
            download()
        }
    }

    /** Hands a downloaded clip to the conversation-owned viewer. */
    fun dispatchViewerOpen() {
        if (transfer.cancelling || sendingFromStagedFile) return
        onOpenConversationMedia(
            ConversationMediaViewerOpenRequest(
                messageIdHex = messageIdHex,
                attachments = listOf(IndexedValue(attachmentIndex, reference)),
                tappedAttachmentIndex = attachmentIndex,
                sender = record.sender,
                recordedAt = record.recordedAt,
                mine = mine,
            ),
        )
    }

    /**
     * Revalidates a cached bubble before handing its logical attachment to the viewer owner. A file evicted since it
     * was downloaded is downloaded again in place rather than opened once it returns.
     */
    suspend fun dispatchReadyVideo() {
        val playableFile =
            withContext(Dispatchers.IO) {
                validatedAttachmentCacheFile(localFile)
            }
        if (playableFile == null) {
            localFile = null
            posterBitmap = null
            durationMs = 0L
            // The intent may already be interactive, so the token restarts the materialization this file ended.
            reloadToken++
            requestDownload()
            return
        }
        localFile = playableFile
        failed = false
        dispatchViewerOpen()
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
    LaunchedEffect(pillKey, retainedPendingVideoBytes) {
        val bytes = retainedPendingVideoBytes ?: return@LaunchedEffect
        if (posterBitmap != null && durationMs > 0L) return@LaunchedEffect
        val frame = withContext(Dispatchers.IO) { pendingVideoPosterFrame(bytes, posterBitmap == null) }
        if (frame.durationMs > 0L) durationMs = frame.durationMs
        val bitmap = frame.bitmap
        if (bitmap != null && posterBitmap == null) {
            controller.cacheThumbnail(messageIdHex, attachmentIndex, bitmap)
            posterBitmap = bitmap.asImageBitmap()
        }
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        // Poster may already be seeded from cache after a sourceEpoch upgrade;
        // still read duration so the label survives, but do not decode another frame.
        if (posterBitmap != null && durationMs > 0L) return@LaunchedEffect
        val needsPoster = posterBitmap == null
        val (bm, dur) =
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
        shape = ConversationRichContentShape,
        modifier = modifier.then(imageBubbleSizing(bubbleAspectRatio, sourceShortSideFromDim(reference.dim))),
    ) {
        Box(contentAlignment = Alignment.Center) {
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

            // Centered play overlay — semi-transparent dark circle with white
            // triangle. While uploading we replace the triangle with a spinner
            // so the user sees the send is in flight (matches the image bubble).
            // When startDownload is gated off (policy says no auto-fetch), the
            // triangle becomes a download icon and tap consents to the fetch,
            // which completes in place without opening the viewer.
            if (transferShown) {
                TileTransferControl(
                    transfer = transfer,
                    onRetry = { requestDownload(retry = true) },
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
                            .size(VIDEO_PLAY_DISC_SIZE)
                            .testTag(videoAttachmentOpenTestTag(messageIdHex, attachmentIndex))
                            .combinedClickable(
                                onLongClick = onLongPress,
                                onClick = {
                                    when {
                                        uploadFailed -> onRetryUpload?.invoke()
                                        localFile != null -> scope.launch { dispatchReadyVideo() }
                                        // The reader's own clip still held in memory for its upload is on the device.
                                        retainedPlaintextOnEntry -> dispatchViewerOpen()
                                        // Nothing is playable until the staged file is sent, and the ring shows it.
                                        sendingFromStagedFile -> Unit
                                        else -> requestDownload()
                                    }
                                },
                            ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            uploadFailed ->
                                Icon(
                                    painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.video_failed),
                                    modifier =
                                        Modifier
                                            .size(28.dp)
                                            .clickable { onRetryUpload?.invoke() },
                                )
                            uploading -> VideoUploadIndicator(uploadProgress)
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
                            loading -> VideoDownloadingRing()
                            failed ->
                                Icon(
                                    painterResource(R.drawable.ic_refresh),
                                    contentDescription = stringResource(R.string.video_failed),
                                    modifier =
                                        Modifier
                                            .size(28.dp)
                                            .clickable(enabled = !loading) { retryFailedPlayback() },
                                )
                            // An accepted download that has not started yet is still a download, never Play.
                            localFile == null && !sendingFromStagedFile -> VideoDownloadingRing()
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
            if (durationMs > 0L) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = VIDEO_DURATION_CHIP_ALPHA),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                ) {
                    Text(
                        formatVoiceTime(durationMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

// The prototype's video affordances: a 44dp inverse-surface disc with a 24dp glyph, and a duration chip.
internal val VIDEO_PLAY_DISC_SIZE = 44.dp
internal const val VIDEO_PLAY_DISC_ALPHA = 0.88f
internal const val VIDEO_DURATION_CHIP_ALPHA = 0.8f

/**
 * The play disc's upload ring, reading [progress] here so only the ring recomposes as bytes move. It is
 * determinate while a share of work is known and spins otherwise, and it names the step and its bytes for
 * TalkBack, as the download ring on a video tile does.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun VideoUploadIndicator(progress: () -> FileUploadProgress?) {
    val current = progress()
    val fraction = current?.ringFraction
    val description = current?.let { uploadProgressDescription(it) }
    val spoken =
        if (description != null) {
            Modifier.semantics { contentDescription = description }
        } else {
            Modifier
        }
    if (fraction != null) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(24.dp).then(spoken),
            strokeWidth = 2.5.dp,
            color = LocalContentColor.current,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp).then(spoken),
            strokeWidth = 2.5.dp,
            color = LocalContentColor.current,
        )
    }
}

/** The video play disc's progress ring while its clip downloads, named as the voice note's Downloading state is. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun VideoDownloadingRing() {
    val downloading = stringResource(R.string.media_downloading)
    CircularProgressIndicator(
        modifier = Modifier.size(24.dp).semantics { contentDescription = downloading },
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}
