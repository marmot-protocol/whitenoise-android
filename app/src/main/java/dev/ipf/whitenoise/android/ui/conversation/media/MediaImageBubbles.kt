package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.HostPerformanceAttemptSlot
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.automaticAttachmentDownloadSuppressed
import dev.ipf.whitenoise.android.state.displayThumbnailRequest
import dev.ipf.whitenoise.android.state.displayThumbnailRequestCurrent
import dev.ipf.whitenoise.android.state.displayThumbnailSessionCurrent
import dev.ipf.whitenoise.android.state.persistDisplayThumbnail
import dev.ipf.whitenoise.android.state.readDisplayThumbnail
import dev.ipf.whitenoise.android.state.readerDownloadParkedForStorage
import dev.ipf.whitenoise.android.state.recordReaderParkedDownload
import dev.ipf.whitenoise.android.state.retryAttachmentTransfer
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward
import dev.ipf.whitenoise.android.ui.icons.filled.Image
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The prototype's single-media frame: 256 dp tall, width from the aspect ratio, capped at 256 dp. */
internal const val SINGLE_MEDIA_MAX_EXTENT_DP = 256f

/** Sources smaller than the frame are shown at 192 dp instead of being upscaled to fill it. */
internal const val SMALL_SOURCE_DISPLAY_EXTENT_DP = 192f

/** Completes media application only after decoded content has executed its draw pass. */
private fun Modifier.reportMediaApplyAfterDraw(attempt: HostPerformanceAttemptSlot): Modifier =
    drawWithContent {
        drawContent()
        attempt.success()
    }

/** Sizing modifier for the optimistic and confirmed single-image or video frame. */
@Composable
internal fun imageBubbleSizing(
    ratio: Float?,
    sourceShortSidePx: Int? = null,
): Modifier {
    val (width, height) = singleMediaSizeDp(ratio, sourceShortSidePx)
    return Modifier.width(width.dp).height(height.dp)
}

/** The prototype dims an album's overflow tile this far before drawing its "+N" label. */
private const val ALBUM_OVERFLOW_SCRIM_ALPHA = 0.58f

/**
 * Decode an imeta `thumbhash` field into a tiny ARGB ImageBitmap, cached
 * for the lifetime of the composition. Returns null when the field is
 * absent or doesn't decode. Callers render the bitmap with
 * [ContentScale.Crop] under the loading state so the bubble shows a
 * blurred preview before the real bytes arrive.
 */
@Composable
internal fun rememberThumbhashImage(thumbhash: String?): ImageBitmap? {
    if (thumbhash.isNullOrBlank()) return null
    // The decode is a few hundred μs to a couple ms (cosine-basis sum
    // across a 32×32 grid). Doing it inside `remember { ... }` runs it on
    // the Compose / Main thread during the initial composition pass, which
    // multiplied across the bubbles entering composition during scroll adds
    // up to a measurable Input+Anim+Layout cost. `produceState` defers the
    // decode to Dispatchers.Default and emits the result when ready —
    // initial composition returns instantly with `null` and the bubble
    // shows the underlying surface tint until the blurred placeholder
    // arrives.
    val state =
        produceState<ImageBitmap?>(initialValue = null, key1 = thumbhash) {
            value =
                withContext(Dispatchers.Default) {
                    Thumbhash.decodeToBitmap(thumbhash)?.asImageBitmap()
                }
        }
    return state.value
}

internal fun initialMediaBubbleAspectRatio(dim: String?): Float? = aspectRatioFromDim(dim)

@Composable
internal fun rememberMediaBubbleAspectRatio(
    messageIdHex: String,
    attachmentIndex: Int,
    dim: String?,
): Float? =
    remember(messageIdHex, attachmentIndex) {
        initialMediaBubbleAspectRatio(dim)
    }

/** Renders retained image pixels, materializes policy-allowed misses, and delegates viewer ownership to the host. */
@Composable
internal fun MediaImageBubble(
    item: TimelineMessage,
    reference: MediaAttachmentReferenceFfi,
    attachmentIndex: Int,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onOpenConversationMedia: (ConversationMediaViewerOpenRequest) -> Unit,
    mine: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val record = item.record
    val key = record.messageIdHex
    val bubbleAspectRatio =
        rememberMediaBubbleAspectRatio(
            messageIdHex = key,
            attachmentIndex = attachmentIndex,
            dim = reference.dim,
        )
    // Bytes-level state is keyed on `sourceEpoch` so a typed-reference upgrade from imeta-fallback (epoch = 0)
    // clears a stuck failure; the materialization intent is not, so that upgrade cannot close a viewer the user
    // opened or re-gate a download they consented to.
    val load =
        rememberImageLoadState(
            controller = controller,
            appState = appState,
            messageIdHex = key,
            attachmentIndex = attachmentIndex,
            reference = reference,
            mine = mine,
            decodeKeys = listOf(controller, key, attachmentIndex, reference.sourceEpoch),
            fallbackAspectRatio = bubbleAspectRatio,
            intentIdentity = "$key#$attachmentIndex",
            failureLogTag = "MediaImageBubble",
            failureLogMessage = "image_auto_download_failed",
        )

    /** Hands the logical image to the conversation-owned viewer before row disposal can occur. */
    fun dispatchViewerOpen() {
        onOpenConversationMedia(
            ConversationMediaViewerOpenRequest(
                messageIdHex = key,
                attachments = listOf(IndexedValue(attachmentIndex, reference)),
                tappedAttachmentIndex = attachmentIndex,
                sender = record.sender,
                recordedAt = record.recordedAt,
                mine = mine,
            ),
        )
    }

    // A GIF is framed by the prototype's fixed banner instead of its own aspect ratio.
    val gifFrame =
        isGifAttachmentMediaType(reference.mediaType) || load.presentation is DecodedAttachmentPresentation.Animated
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ConversationRichContentShape,
        // Single source of truth for image-bubble shape: portraits become
        // uniform-width cards (capped height), landscapes fill the bubble
        // width. Used by both the confirmed bubble and the optimistic
        // upload-phase bubble so the optimistic → confirmed swap is a
        // visual no-op.
        modifier =
            modifier.onSizeChanged(load.decodeRequest::onSizeChanged).then(
                if (gifFrame) {
                    Modifier
                        .width(ConversationMessageMetrics.RichContentCanvasWidth)
                        .height(ConversationMessageMetrics.GifHeight)
                } else {
                    imageBubbleSizing(bubbleAspectRatio, sourceShortSideFromDim(reference.dim))
                },
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val downloadLabel = stringResource(R.string.media_tap_to_download)
            val downloadingLabel = stringResource(R.string.media_downloading)
            val openingLabel = stringResource(R.string.media_opening)
            val openLabel = stringResource(R.string.media_open)
            val current = load.presentation
            val placeholder = rememberThumbhashImage(reference.thumbhash)
            // Paint the blurred placeholder behind whatever loading-state is
            // shown so the bubble has a perceptual preview before the real
            // bytes arrive. The real image (when `current != null`) covers it.
            if (current == null && placeholder != null) {
                Image(
                    bitmap = placeholder,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when (current) {
                is DecodedAttachmentPresentation.Static ->
                    InlineAttachmentImage(
                        still = current,
                        source = load.animationSource,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    interactionSource = null,
                                    indication = rememberMediaViewerPressIndication(),
                                    onLongClick = onLongPress,
                                    onClick = ::dispatchViewerOpen,
                                ).mediaViewerThumbnail(key, attachmentIndex, reference.sourceEpoch)
                                .reportMediaApplyAfterDraw(load.mediaApplyHostAttempt),
                    )
                is DecodedAttachmentPresentation.Animated ->
                    AnimatedDrawableAttachmentImage(
                        drawable = current.drawable,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    interactionSource = null,
                                    indication = rememberMediaViewerPressIndication(),
                                    onLongClick = onLongPress,
                                    onClick = ::dispatchViewerOpen,
                                ).mediaViewerThumbnail(key, attachmentIndex, reference.sourceEpoch)
                                .reportMediaApplyAfterDraw(load.mediaApplyHostAttempt),
                    )
                null ->
                    when {
                        load.tooLargeToPreview ->
                            MediaTooLargeToPreviewControl(onOpen = ::dispatchViewerOpen, showCaption = true)
                        // A load parked for lack of free space names storage beside its Retry.
                        load.failed && load.storageFailed ->
                            TileTransferControl(
                                transfer = storageBlockedTileTransfer(),
                                onRetry = {
                                    controller.retryAttachmentTransfer(
                                        key,
                                        attachmentIndex,
                                        onAccepted = { controller.requestAttachmentDownload(key, attachmentIndex) },
                                        onFailure = { load.markFailed(it) },
                                    )
                                },
                                showCaption = true,
                            )
                        load.failed ->
                            MediaCircleAction(
                                icon = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.media_tap_to_retry),
                                onClick = {
                                    controller.retryAttachmentTransfer(
                                        key,
                                        attachmentIndex,
                                        onAccepted = { controller.requestAttachmentDownload(key, attachmentIndex) },
                                        onFailure = { load.markFailed(it) },
                                    )
                                },
                            )
                        load.transfer.visible ->
                            TileTransferControl(
                                transfer = load.transfer,
                                onRetry = {
                                    controller.retryAttachmentTransfer(
                                        key,
                                        attachmentIndex,
                                        onAccepted = { controller.requestAttachmentDownload(key, attachmentIndex) },
                                        onFailure = { load.markFailed(it) },
                                    )
                                },
                                showCaption = true,
                            )
                        // Until the retained-bytes probes answer, a file MDK holds must not flash a Download action.
                        !load.startDownload && !load.downloadActionReady -> Unit
                        !load.startDownload ->
                            MediaCircleAction(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = downloadLabel,
                                onClick = {
                                    controller.requestAttachmentDownload(key, attachmentIndex)
                                },
                            )
                        // Bytes already on the device are only being read, so a tap opens the viewer now. A download
                        // runs in place instead, and the image opens only from a tap once it has arrived.
                        else ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .then(
                                            if (load.cachedPlaintextOnEntry || load.retainedPlaintextOnEntry) {
                                                Modifier
                                                    .semantics { contentDescription = openingLabel }
                                                    .clickable(onClickLabel = openLabel, onClick = ::dispatchViewerOpen)
                                            } else {
                                                Modifier.semantics { contentDescription = downloadingLabel }
                                            },
                                        ),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
            }
            if (uploading) {
                Surface(
                    color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Count-specific masonry scaffolding for a 2-4 image album. Lays out the
 * tiles so a 3-image set is tall-left + two-stacked-right (no empty cell),
 * and 4+ is a 2×2 grid where the fourth tile carries the "+N" overflow chip
 * (#527). Caller provides the per-tile composable through [tile]; the helper
 * supplies each tile its size modifier so the layout shape stays one source
 * of truth across the confirmed bubble and the optimistic upload-phase
 * placeholder.
 */
@Composable
@Suppress("MagicNumber", "FunctionNaming")
internal fun MasonryImageLayout(
    visibleCount: Int,
    onLongPress: () -> Unit = {},
    tile: @Composable (index: Int, tileModifier: Modifier) -> Unit,
) {
    val gap = ConversationMessageMetrics.GallerySpacing
    MediaThumbnailCanvas {
        when (visibleCount) {
            2 ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth().height(GALLERY_TWO_HEIGHT),
                ) {
                    tile(0, Modifier.weight(1f).fillMaxHeight())
                    tile(1, Modifier.weight(1f).fillMaxHeight())
                }
            3 ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth().height(GALLERY_THREE_HEIGHT),
                ) {
                    tile(0, Modifier.width(GALLERY_THREE_HEIGHT).fillMaxHeight())
                    Column(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        tile(1, Modifier.weight(1f).fillMaxWidth())
                        tile(2, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            4 -> masonryFourImageLayout(gap, tile)
            else ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth().height(GALLERY_FIVE_HEIGHT),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                        tile(0, Modifier.weight(1f).height(GALLERY_TWO_HEIGHT))
                        tile(1, Modifier.weight(1f).height(GALLERY_TWO_HEIGHT))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                        for (index in 2 until visibleCount.coerceAtMost(MAX_VISIBLE_GALLERY_FRAMES)) {
                            tile(index, Modifier.weight(1f).height(GALLERY_FIVE_SECOND_ROW_HEIGHT))
                        }
                    }
                }
        }
    }
}

/** Keeps the four-tile grid's native row and weight structure unchanged. */
@Composable
private fun masonryFourImageLayout(
    gap: Dp,
    tile: @Composable (index: Int, tileModifier: Modifier) -> Unit,
) {
    val bottomRight = 3
    Column(
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxWidth().height(GALLERY_FOUR_HEIGHT),
    ) {
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            tile(0, Modifier.weight(1f).fillMaxHeight())
            tile(1, Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            tile(2, Modifier.weight(1f).fillMaxHeight())
            tile(bottomRight, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Galleries show at most five frames; the last one carries the "+N" overflow. */
internal const val MAX_VISIBLE_GALLERY_FRAMES = 5
private val GALLERY_TWO_HEIGHT = 127.dp
private val GALLERY_THREE_HEIGHT = 170.dp
private val GALLERY_FOUR_HEIGHT = 256.dp
private val GALLERY_FIVE_HEIGHT = 213.dp
private val GALLERY_FIVE_SECOND_ROW_HEIGHT = 84.dp

/** Gallery grid of up to five frames with the +N overflow tile. */
@Composable
internal fun MediaVisualGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onOpenConversationMedia: (ConversationMediaViewerOpenRequest) -> Unit,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
) {
    val record = item.record
    // Five frames at most; the last one carries the "+N" overflow chip.
    val visible = attachments.take(MAX_VISIBLE_GALLERY_FRAMES)

    /**
     * Opens one grid attachment through the row-independent mixed-media session owner. A video still being sent from
     * its staged file has nothing to show yet, so the viewer leaves it out, and a tap that names it, as its "+N"
     * overflow tile does, opens at the next item that can be shown.
     */
    fun dispatchViewerOpen(attachmentIndex: Int) {
        val openable =
            attachments.filterNot { controller.isSendingFromStagedFile(record.messageIdHex, it.index, mine) }
        val opening =
            openable.firstOrNull { it.index >= attachmentIndex } ?: openable.lastOrNull() ?: return
        onOpenConversationMedia(
            ConversationMediaViewerOpenRequest(
                messageIdHex = record.messageIdHex,
                attachments = openable,
                tappedAttachmentIndex = opening.index,
                sender = record.sender,
                recordedAt = record.recordedAt,
                mine = mine,
            ),
        )
    }

    val tileAt: @Composable (Int, Modifier) -> Unit = { tileIndex, tileModifier ->
        val entry = visible[tileIndex]
        val showOverflow = tileIndex == visible.lastIndex && attachments.size > visible.size
        if (MediaReferenceSupport.isVideoMedia(entry.value)) {
            MediaVideoGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { dispatchViewerOpen(entry.index) },
                overflowCount = if (showOverflow) attachments.size - visible.size else 0,
                modifier = tileModifier,
                onLongPress = onLongPress,
                uploading = uploading,
            )
        } else {
            MediaImageGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { dispatchViewerOpen(entry.index) },
                overflowCount = if (showOverflow) attachments.size - visible.size else 0,
                modifier = tileModifier,
                onLongPress = onLongPress,
                uploading = uploading,
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = ConversationRichContentShape,
        modifier = Modifier.width(ConversationMessageMetrics.RichContentCanvasWidth),
    ) {
        MasonryImageLayout(visibleCount = visible.size, onLongPress = onLongPress, tile = tileAt)
    }
}

/**
 * One tile of the album grid: square thumbnail + per-tile download state.
 * The thumbnail-cache lookup is keyed on `(messageId, attachmentIndex)` so
 * tiles never clobber each other. Tap fires [onTap] (the parent opens the
 * full-screen viewer at this attachment's index).
 */
@Composable
internal fun MediaImageGridTile(
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
) {
    // Two-bucket key model: `decodeKey` includes `sourceEpoch` and scopes bytes-level state; the intent identity
    // omits it so a background ref upgrade cannot re-gate a tile the user already consented to fetch.
    val decodeKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    val load =
        rememberImageLoadState(
            controller = controller,
            appState = appState,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            mine = mine,
            decodeKeys = listOf(controller, decodeKey),
            fallbackAspectRatio = aspectRatioFromDim(reference.dim),
            intentIdentity = "$messageIdHex#$attachmentIndex",
            failureLogTag = "MediaImageGridTile",
            failureLogMessage = "image_tile_auto_download_failed",
        )

    Box(
        modifier =
            modifier.onSizeChanged(load.decodeRequest::onSizeChanged).combinedClickable(
                interactionSource = null,
                indication =
                    rememberMediaViewerPressIndication(
                        ready = load.presentation != null && !load.transfer.cancelling,
                    ),
                onLongClick = onLongPress,
                // Two modes:
                //   - Bytes ready (`bitmap != null`): tap opens the viewer.
                //   - Bytes pending: tap persists an interactive download that
                //     decodes in place and never opens the viewer by itself.
                // While the reader's Cancel awaits acknowledgement a tap does nothing, so it cannot restart it.
                onClick = {
                    when {
                        load.transfer.cancelling -> Unit
                        load.tooLargeToPreview -> onTap()
                        // A visible image opens the viewer even if its sharper decode failed, as in the single bubble.
                        load.presentation != null -> onTap()
                        load.failed ->
                            controller.retryAttachmentTransfer(
                                messageIdHex,
                                attachmentIndex,
                                onAccepted = { controller.requestAttachmentDownload(messageIdHex, attachmentIndex) },
                                onFailure = { load.markFailed(it) },
                            )
                        // Bytes already on the device, cached or the reader's own still held for upload, are only
                        // being read, so the tap opens the viewer now rather than downloading.
                        load.cachedPlaintextOnEntry || load.retainedPlaintextOnEntry -> onTap()
                        else -> controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val current = load.presentation
        val placeholder = rememberThumbhashImage(reference.thumbhash)
        if (current == null && placeholder != null) {
            Image(
                bitmap = placeholder,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (current) {
            is DecodedAttachmentPresentation.Static ->
                InlineAttachmentImage(
                    still = current,
                    source = load.animationSource,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .mediaViewerThumbnail(messageIdHex, attachmentIndex, reference.sourceEpoch, gridTile = true)
                            .reportMediaApplyAfterDraw(load.mediaApplyHostAttempt),
                )
            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .mediaViewerThumbnail(messageIdHex, attachmentIndex, reference.sourceEpoch, gridTile = true)
                            .reportMediaApplyAfterDraw(load.mediaApplyHostAttempt),
                )
            null ->
                when {
                    load.tooLargeToPreview -> MediaTooLargeToPreviewControl(onOpen = onTap)
                    load.failed ->
                        MediaCircleAction(
                            icon = Icons.Default.Refresh,
                            contentDescription =
                                stringResource(
                                    if (load.storageFailed) {
                                        R.string.media_storage_blocked
                                    } else {
                                        R.string.media_tap_to_retry
                                    },
                                ),
                            onClick = {
                                controller.retryAttachmentTransfer(
                                    messageIdHex,
                                    attachmentIndex,
                                    onAccepted = {
                                        controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
                                    },
                                    onFailure = { load.markFailed(it) },
                                )
                            },
                        )
                    load.transfer.visible ->
                        TileTransferControl(
                            transfer = load.transfer,
                            onRetry = {
                                controller.retryAttachmentTransfer(
                                    messageIdHex,
                                    attachmentIndex,
                                    onAccepted = {
                                        controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
                                    },
                                    onFailure = { load.markFailed(it) },
                                )
                            },
                        )
                    // Until the retained-bytes probes answer, a file MDK holds must not flash a Download action.
                    !load.startDownload && !load.downloadActionReady -> Unit
                    !load.startDownload ->
                        MediaCircleAction(
                            icon = Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.media_tap_to_download),
                            onClick = {
                                controller.requestAttachmentDownload(messageIdHex, attachmentIndex)
                            },
                        )
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                }
        }
        if (overflowCount > 0 && current != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = ALBUM_OVERFLOW_SCRIM_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha.FAINT)),
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

/** Everything an image surface's layout reads from [rememberImageLoadState]. */
internal class ImageLoadState(
    val decodeRequest: ThumbnailDecodeRequest,
    val presentation: DecodedAttachmentPresentation?,
    val failed: Boolean,
    val storageFailed: Boolean,
    val tooLargeToPreview: Boolean,
    val transfer: TileTransfer,
    val startDownload: Boolean,
    val downloadActionReady: Boolean,
    val cachedPlaintextOnEntry: Boolean,
    val retainedPlaintextOnEntry: Boolean,
    val animationSource: InlineAnimationSource,
    val mediaApplyHostAttempt: HostPerformanceAttemptSlot,
    val markFailed: (Throwable?) -> Unit,
)

/**
 * The image load shared by the single bubble and album tiles: thumbnail cache seed, failed, storage-parked and
 * too-large state, the automatic download gate, the materialization intent, the bytes-to-decode effect and the
 * failure handling. Callers pass the remember [decodeKeys] for bytes-level state, the [intentIdentity] for the
 * user-choice state, and the log tag and message of the failure line; each keeps its own layout and viewer open.
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun rememberImageLoadState(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    decodeKeys: List<Any>,
    fallbackAspectRatio: Float?,
    intentIdentity: String,
    failureLogTag: String,
    failureLogMessage: String,
): ImageLoadState {
    val keys = decodeKeys.toTypedArray()
    val cachedThumbnail = remember(*keys) { controller.thumbnailEntryFor(messageIdHex, attachmentIndex) }
    val decodeRequest =
        remember(*keys) {
            val cachedRatio = cachedThumbnail?.bitmap?.let { it.width.toFloat() / it.height }
            ThumbnailDecodeRequest(cachedRatio ?: fallbackAspectRatio)
        }
    val requestedMaxEdgePx = decodeRequest.maxEdgePx
    var presentationMaxEdgePx by remember(*keys) { mutableIntStateOf(cachedThumbnail?.maxEdgePx ?: 0) }
    // A derivative proves display readiness only, never completion of a persisted original-download intent.
    var originalMaterialized by remember(*keys) { mutableStateOf(false) }
    // Seed from the decoded-thumbnail cache so an already-fetched or just-sent
    // image paints on the first frame — no decode spinner, no visible "reload".
    // Animated GIF/WebP and byte-sniffed unknowns skip the static thumbnail
    // cache so they always decode through the ImageDecoder path.
    var presentation by remember(*keys) {
        val cached =
            if (MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
                cachedThumbnail?.bitmap
            } else {
                null
            }
        mutableStateOf<DecodedAttachmentPresentation?>(
            cached?.let { DecodedAttachmentPresentation.Static(it) },
        )
    }
    val animationSource =
        rememberInlineAnimationSource(
            decodeKeys,
            appState,
            controller.boundAccountRef,
            controller.group.groupIdHex,
        ) {
            localAttachmentBytes(controller, messageIdHex, attachmentIndex, mine)
        }
    val mediaApplyHostAttempt = remember(*keys) { HostPerformanceAttemptSlot() }
    DisposableEffect(mediaApplyHostAttempt) {
        onDispose(mediaApplyHostAttempt::cancel)
    }
    var failed by remember(*keys) { mutableStateOf(false) }
    var storageFailed by remember(*keys) { mutableStateOf(false) }

    /** Fails this attachment, saying "Not enough storage" only when [cause] is a storage park. */
    fun markFailed(cause: Throwable? = null) {
        storageFailed = cause.parksForStorage()
        failed = true
    }
    // A verified image above the preview budget is not a failure: Retry cannot shrink it, so the surface opens the
    // viewer instead, where Save and Share still work.
    var tooLargeToPreview by remember(*keys) { mutableStateOf(false) }
    var reloadToken by remember(*keys) { mutableIntStateOf(0) }
    storageReadmissionEffect(controller, messageIdHex, attachmentIndex, parked = failed && storageFailed) {
        failed = false
        storageFailed = false
        reloadToken++
    }
    val cacheAvailability =
        rememberImageAttachmentCacheAvailability(
            controller,
            messageIdHex,
            attachmentIndex,
            reference.sourceEpoch,
            presentation != null,
        )
    var cachedPlaintextOnEntry by cacheAvailability.cached
    val retainedPlaintextOnEntry =
        mine && controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex)?.inMemoryBytes != null
    // Auto-download gating (#10): retained/cached own bytes always render;
    // idle network fallback obeys policy. Once accepted, materialization stays
    // latched across policy recomposition so an active UI waiter is not lost.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    // The reader's Cancel is persisted per attachment, so policy cannot restart what they stopped.
    val cancelledByReader = controller.automaticAttachmentDownloadSuppressed(messageIdHex, attachmentIndex)
    val autoAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image) && !cancelledByReader
    val automaticNetworkAllowed by rememberUpdatedState(
        shouldMaterializeAttachmentAutomatically(mine, autoAllowed, automaticDownloadsPaused),
    )
    val policyAllowsMaterialization =
        shouldMaterializeAttachmentAutomatically(
            mine = mine,
            mediaAutoDownloadAllowed = autoAllowed,
            automaticDownloadsPaused = automaticDownloadsPaused,
            hasCachedAttachment = cachedPlaintextOnEntry,
            hasRetainedPlaintext = retainedPlaintextOnEntry,
        )
    var appliedPolicy by remember { mutableStateOf<Boolean?>(null) }
    var materializationIntent by
        rememberAttachmentMaterializationIntent(
            identity = intentIdentity,
            policyAllowsMaterialization = policyAllowsMaterialization,
            onPolicyApplied = { appliedPolicy = it },
        )
    val startDownload = materializationIntent.shouldMaterialize
    val cacheAvailabilityResolved by cacheAvailability.resolved
    // Offer Download only once the probes answered and the intent has absorbed the policy they produced.
    val downloadActionReady = cacheAvailabilityResolved && appliedPolicy == policyAllowsMaterialization
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
        )

    LaunchedEffect(*keys, materializationIntent, reloadToken, requestedMaxEdgePx) {
        if (requestedMaxEdgePx == 0) return@LaunchedEffect
        val fulfillOriginalDownload =
            materializationIntent.readerRequested &&
                controller.hasAttachmentDownloadIntent(messageIdHex, attachmentIndex)
        if (presentation != null && presentationMaxEdgePx >= requestedMaxEdgePx && !fulfillOriginalDownload) {
            return@LaunchedEffect
        }
        val thumbnailRequest =
            controller.displayThumbnailRequest(messageIdHex, attachmentIndex, reference)
                ?: return@LaunchedEffect
        val cached = controller.thumbnailEntryFor(messageIdHex, attachmentIndex, requestedMaxEdgePx)
        val seedStatic = MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)
        if (!fulfillOriginalDownload && cached != null && seedStatic) {
            presentation = DecodedAttachmentPresentation.Static(cached.bitmap)
            presentationMaxEdgePx = cached.maxEdgePx
            decodeRequest.onDecodedSize(cached.bitmap.width, cached.bitmap.height)
            return@LaunchedEffect
        }
        if (!fulfillOriginalDownload && MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
            val persisted = appState.readDisplayThumbnail(thumbnailRequest, requestedMaxEdgePx)
            if (persisted != null) {
                controller.cacheThumbnail(messageIdHex, attachmentIndex, persisted.bitmap, persisted.maxEdgePx)
                presentation = DecodedAttachmentPresentation.Static(persisted.bitmap)
                presentationMaxEdgePx = persisted.maxEdgePx
                decodeRequest.onDecodedSize(persisted.bitmap.width, persisted.bitmap.height)
                return@LaunchedEffect
            }
        }
        if (!appState.displayThumbnailRequestCurrent(thumbnailRequest)) {
            if (appState.displayThumbnailSessionCurrent(thumbnailRequest)) reloadToken++
            return@LaunchedEffect
        }
        if (!startDownload) return@LaunchedEffect
        failed = false
        tooLargeToPreview = false
        try {
            val data =
                imageAttachmentBytes(
                    controller = controller,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                    mine = mine,
                    priority = materializationIntent.priority,
                    allowNetwork = attachmentTransferAllowsNetwork(materializationIntent, automaticNetworkAllowed),
                ) ?: run {
                    cachedPlaintextOnEntry = false
                    materializationIntent =
                        AttachmentMaterializationIntent.Idle.withPolicyAllowed(automaticNetworkAllowed)
                    // A grant during the local read preserves the intent, so it needs a new effect key.
                    if (automaticNetworkAllowed) reloadToken++
                    return@LaunchedEffect
                }
            if (!appState.displayThumbnailRequestCurrent(thumbnailRequest)) {
                if (appState.displayThumbnailSessionCurrent(thumbnailRequest)) reloadToken++
                return@LaunchedEffect
            }
            if (presentation != null && presentationMaxEdgePx >= requestedMaxEdgePx) {
                originalMaterialized = true
                return@LaunchedEffect
            }
            val decoded =
                appState.measureHostPerformance(HostPerformanceOperationFfi.MEDIA_DECODE) {
                    decodeMessageAttachmentImage(
                        bytes = data,
                        mediaType = reference.mediaType,
                        staticMaxEdgePx = requestedMaxEdgePx,
                        domain = controller.mediaTrustDomain,
                    )
                }
            if (!appState.displayThumbnailRequestCurrent(thumbnailRequest)) {
                decoded?.releaseOwnedPresentation()
                if (appState.displayThumbnailSessionCurrent(thumbnailRequest)) reloadToken++
                return@LaunchedEffect
            }
            originalMaterialized = true
            if (decoded != null) {
                if (decoded is DecodedAttachmentPresentation.Static && !decoded.animationDeferred) {
                    controller.cacheThumbnail(messageIdHex, attachmentIndex, decoded.bitmap, requestedMaxEdgePx)
                }
                mediaApplyHostAttempt.replace(
                    appState.beginHostPerformance(HostPerformanceOperationFfi.MEDIA_APPLY),
                )
                presentation = decoded
                presentationMaxEdgePx = requestedMaxEdgePx
                if (decoded is DecodedAttachmentPresentation.Static) {
                    decodeRequest.onDecodedSize(decoded.bitmap.width, decoded.bitmap.height)
                    val persistStatic =
                        !decoded.animationDeferred &&
                            MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)
                    if (persistStatic) {
                        appState.persistDisplayThumbnail(thumbnailRequest, decoded.bitmap, requestedMaxEdgePx)
                    }
                }
            } else {
                markFailed()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            materializationIntent = materializationIntent.afterProducerCancellation(cancel)
        } catch (_: AttachmentTooLargeToPresentException) {
            tooLargeToPreview = true
        } catch (
            // Any failure leaves Retry; only a storage-parked body changes what it says.
            @Suppress("TooGenericExceptionCaught") failure: Throwable,
        ) {
            android.util.Log.w(failureLogTag, failureLogMessage)
            markFailed(failure)
        }
    }

    persistedAttachmentDownloadEffect(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        sourceEpoch = reference.sourceEpoch,
        controller = controller,
        appState = appState,
        // A download of an image that turned out too large to preview is settled once that is known.
        isReady = { originalMaterialized || tooLargeToPreview },
        ensureMaterialization = {
            if (failed) {
                failed = false
                reloadToken++
            }
            materializationIntent = materializationIntent.afterInteractiveRequest()
        },
    )
    return ImageLoadState(
        decodeRequest = decodeRequest,
        presentation = presentation,
        failed = failed,
        storageFailed = storageFailed,
        tooLargeToPreview = tooLargeToPreview,
        transfer = transfer,
        startDownload = startDownload,
        downloadActionReady = downloadActionReady,
        cachedPlaintextOnEntry = cachedPlaintextOnEntry,
        retainedPlaintextOnEntry = retainedPlaintextOnEntry,
        animationSource = animationSource,
        mediaApplyHostAttempt = mediaApplyHostAttempt,
        markFailed = { markFailed(it) },
    )
}
