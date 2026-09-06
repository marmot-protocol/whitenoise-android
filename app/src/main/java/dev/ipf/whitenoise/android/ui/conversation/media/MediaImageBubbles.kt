package dev.ipf.whitenoise.android.ui.conversation.media

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixed height of an in-timeline image bubble — constant across load states
 *  so async decode never reflows the list (would break the open-time anchor). */
private val MediaBubbleHeight = 240.dp

/** Hard cap on the height a `dim`-shaped image bubble can claim, so a tall
 *  portrait can't dominate the chat viewport. Width fills the bubble; this
 *  bounds the height so the aspect-ratio sizing degrades to a cropped
 *  preview at the extremes. */
private val MediaBubbleMaxHeight = 340.dp

/** Fixed card width used for portrait image bubbles, so every portrait
 *  reads as a consistently-sized card rather than a width-varying strip.
 *  Landscape bubbles still fill the parent. */
private val MediaBubbleCardWidth = 280.dp

/** Sizing modifier for both the optimistic and the confirmed single-image
 *  bubble. Portrait images become uniform-width cards with a height cap;
 *  landscape images fill the bubble width and derive their natural height
 *  (which can't exceed the width for ratio ≥ 1). Falls back to the legacy
 *  fixed-height slab when the aspect ratio is unknown. */
@Composable
internal fun imageBubbleSizing(ratio: Float?): Modifier =
    when {
        ratio == null -> Modifier.fillMaxWidth().height(MediaBubbleHeight)
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> {
            val natural = (MediaBubbleCardWidth.value / ratio).dp
            val height = if (natural > MediaBubbleMaxHeight) MediaBubbleMaxHeight else natural
            Modifier.width(MediaBubbleCardWidth).height(height)
        }
    }

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

/**
 * Parse the imeta `dim` field ("WxH") into a width/height aspect ratio.
 * Returns null when [dim] is null, blank, malformed, or non-positive on
 * either axis. Caller falls back to [MediaBubbleHeight] in that case.
 */
internal fun aspectRatioFromDim(dim: String?): Float? {
    if (dim.isNullOrBlank()) return null
    val parts = dim.split('x', 'X', ignoreCase = true)
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    if (w <= 0 || h <= 0) return null
    // Clamp wide panoramas so the bubble doesn't squeeze to a sliver.
    // Tall portraits are bounded by [MediaBubbleMaxHeight] at the layout
    // site instead — keeping the aspect ratio uncramped lets the placeholder
    // still convey "this is a tall image" before the bytes arrive.
    return (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
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

/** Renders retained image pixels immediately and materializes misses according to the user's download policy. */
@Composable
internal fun MediaImageBubble(
    item: TimelineMessage,
    reference: MediaAttachmentReferenceFfi,
    attachmentIndex: Int,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    conversationVisualPages: List<MediaViewerPage>,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    attachedToCaption: Boolean = false,
) {
    val record = item.record
    val key = record.messageIdHex
    val cachedThumbnail =
        remember(key, attachmentIndex) {
            controller.thumbnailFor(key, attachmentIndex)
        }
    val bubbleAspectRatio =
        rememberMediaBubbleAspectRatio(
            messageIdHex = key,
            attachmentIndex = attachmentIndex,
            dim = reference.dim,
        )
    // Decode-state keys split into two buckets:
    //   - Bytes-level state (bitmap, failed, reloadToken): keyed on
    //     `sourceEpoch` so a typed-reference upgrade from imeta-fallback
    //     (epoch = 0) to the real listMedia value clears a stuck failure.
    //   - User-interaction state (viewerOpen, materialization intent): NOT keyed on
    //     epoch, because we never want a background typed-ref upgrade to
    //     close a viewer the user just opened, or re-gate a download the
    //     user just consented to.
    val epoch = reference.sourceEpoch
    // Seed from the decoded-thumbnail cache so an already-fetched or just-sent
    // image paints on the first frame — no decode spinner, no visible "reload".
    // Animated GIF/WebP and byte-sniffed unknowns skip the static thumbnail
    // cache so they always decode through the ImageDecoder path.
    var presentation by remember(key, attachmentIndex, epoch) {
        val cached =
            if (MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
                cachedThumbnail
            } else {
                null
            }
        mutableStateOf<DecodedAttachmentPresentation?>(
            cached?.let { DecodedAttachmentPresentation.Static(it) },
        )
    }
    var failed by remember(key, attachmentIndex, epoch) { mutableStateOf(false) }
    var viewerOpen by remember(key, attachmentIndex) { mutableStateOf(false) }
    var reloadToken by remember(key, attachmentIndex, epoch) { mutableIntStateOf(0) }
    var cachedPlaintextOnEntry by
        rememberImageAttachmentCacheAvailability(controller, key, attachmentIndex, epoch, presentation != null)
    val retainedPlaintextOnEntry =
        mine && controller.pendingAttachmentsList(key).getOrNull(attachmentIndex) != null
    // Auto-download gating (#10): retained/cached own bytes always render;
    // idle network fallback obeys policy. Once accepted, materialization stays
    // latched across policy recomposition so an active UI waiter is not lost.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    val automaticNetworkAllowed by rememberUpdatedState(
        shouldMaterializeAttachmentAutomatically(
            mine,
            appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused,
        ),
    )
    val policyAllowsMaterialization =
        shouldMaterializeAttachmentAutomatically(
            mine = mine,
            mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused = automaticDownloadsPaused,
            hasCachedAttachment = cachedPlaintextOnEntry,
            hasRetainedPlaintext = retainedPlaintextOnEntry,
        )
    var materializationIntent by
        rememberAttachmentMaterializationIntent(
            identity = "$key#$attachmentIndex",
            policyAllowsMaterialization = policyAllowsMaterialization,
        )
    val startDownload = materializationIntent.shouldMaterialize

    LaunchedEffect(key, attachmentIndex, epoch, materializationIntent, reloadToken) {
        if (presentation != null) return@LaunchedEffect // already have decoded pixels
        if (!startDownload) return@LaunchedEffect
        failed = false
        try {
            val data =
                imageAttachmentBytes(
                    controller = controller,
                    messageIdHex = key,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                    mine = mine,
                    priority = materializationIntent.priority,
                    allowNetwork =
                        materializationIntent == AttachmentMaterializationIntent.Interactive || automaticNetworkAllowed,
                ) ?: run {
                    cachedPlaintextOnEntry = false
                    materializationIntent =
                        AttachmentMaterializationIntent.Idle.withPolicyAllowed(automaticNetworkAllowed)
                    // A grant during the local read preserves the intent, so it needs a new effect key.
                    if (automaticNetworkAllowed) reloadToken++
                    return@LaunchedEffect
                }
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                )
            if (decoded != null) {
                if (decoded is DecodedAttachmentPresentation.Static) {
                    controller.cacheThumbnail(key, attachmentIndex, decoded.bitmap)
                }
                presentation = decoded
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            materializationIntent = materializationIntent.afterProducerCancellation(cancel)
        } catch (_: Throwable) {
            android.util.Log.w("MediaImageBubble", "image_auto_download_failed")
            failed = true
        }
    }

    persistedAttachmentOpenEffect(
        messageIdHex = key,
        attachmentIndex = attachmentIndex,
        sourceEpoch = epoch,
        controller = controller,
        appState = appState,
        isReady = { presentation != null },
        ensureMaterialization = {
            if (failed) {
                failed = false
                reloadToken++
            }
            materializationIntent = materializationIntent.afterInteractiveRequest()
        },
        dispatchOpen = { viewerOpen = true },
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = visualMediaBubbleShape(attachedToCaption),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        // Single source of truth for image-bubble shape: portraits become
        // uniform-width cards (capped height), landscapes fill the bubble
        // width. Used by both the confirmed bubble and the optimistic
        // upload-phase bubble so the optimistic → confirmed swap is a
        // visual no-op.
        modifier = imageBubbleSizing(bubbleAspectRatio),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val downloadLabel = stringResource(R.string.media_tap_to_download)
            val current = presentation
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
                    Image(
                        bitmap = current.toImageBitmap(),
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onLongClick = onLongPress,
                                    onClick = { viewerOpen = true },
                                ),
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
                                    onLongClick = onLongPress,
                                    onClick = { viewerOpen = true },
                                ),
                    )
                null ->
                    when {
                        failed ->
                            MediaCircleAction(
                                icon = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.media_tap_to_retry),
                                onClick = {
                                    controller.requestAttachmentOpen(key, attachmentIndex)
                                },
                            )
                        !startDownload ->
                            MediaCircleAction(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = downloadLabel,
                                onClick = {
                                    controller.requestAttachmentOpen(key, attachmentIndex)
                                },
                            )
                        else ->
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .semantics { contentDescription = downloadLabel }
                                        .clickable(
                                            onClickLabel = downloadLabel,
                                            onClick = {
                                                controller.requestAttachmentOpen(key, attachmentIndex)
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

    if (viewerOpen) {
        ConversationMediaViewer(
            controller = controller,
            appState = appState,
            conversationVisualPages = conversationVisualPages,
            messageIdHex = key,
            attachments = listOf(IndexedValue(attachmentIndex, reference)),
            tappedAttachmentIndex = attachmentIndex,
            sender = record.sender,
            recordedAt = record.recordedAt,
            mine = mine,
            onDismiss = { viewerOpen = false },
        )
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
internal fun MasonryImageLayout(
    visibleCount: Int,
    onLongPress: () -> Unit = {},
    tile: @Composable (index: Int, tileModifier: Modifier) -> Unit,
) {
    when (visibleCount) {
        2 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                tile(0, Modifier.weight(1f).aspectRatio(1f))
                tile(1, Modifier.weight(1f).aspectRatio(1f))
            }
        3 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp).aspectRatio(1f),
            ) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
        else ->
            // 4 tiles in a 2×2 grid; any attachments beyond the fourth collapse
            // into the "+N" overflow chip the caller draws on the fourth tile
            // (index 3, the last visible tile) (#527).
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(0, Modifier.weight(1f).aspectRatio(1f))
                    tile(1, Modifier.weight(1f).aspectRatio(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(2, Modifier.weight(1f).aspectRatio(1f))
                    tile(3, Modifier.weight(1f).aspectRatio(1f))
                }
            }
    }
}

/**
 * Mixed image + video album bubble. Each tile picks its renderer based on
 * MIME — image tiles open the image viewer, video tiles tap-to-play in the
 * fullscreen ExoPlayer. Layout is the shared [MasonryImageLayout] masonry.
 */
@Composable
internal fun MediaVisualGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    conversationVisualPages: List<MediaViewerPage>,
    mine: Boolean,
    onLongPress: () -> Unit = {},
    uploading: Boolean = false,
    attachedToCaption: Boolean = false,
) {
    val record = item.record
    // Show up to four tiles before collapsing the remainder into a "+N"
    // overlay on the fourth tile, matching the image grid (#527).
    val visible = attachments.take(4)
    var viewerOpenAttachmentIndex by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

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
                onTap = { _ -> viewerOpenAttachmentIndex = entry.index },
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
                onTap = { viewerOpenAttachmentIndex = entry.index },
                overflowCount = if (showOverflow) attachments.size - visible.size else 0,
                modifier = tileModifier,
                onLongPress = onLongPress,
                uploading = uploading,
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = visualMediaBubbleShape(attachedToCaption),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        MasonryImageLayout(visibleCount = visible.size, onLongPress = onLongPress, tile = tileAt)
    }

    viewerOpenAttachmentIndex?.let { tappedAttachmentIndex ->
        ConversationMediaViewer(
            controller = controller,
            appState = appState,
            conversationVisualPages = conversationVisualPages,
            messageIdHex = record.messageIdHex,
            attachments = attachments,
            tappedAttachmentIndex = tappedAttachmentIndex,
            sender = record.sender,
            recordedAt = record.recordedAt,
            mine = mine,
            onDismiss = { viewerOpenAttachmentIndex = null },
        )
    }
}

@Suppress("MaxLineLength")
internal fun visualMediaBubbleShape(attachedToCaption: Boolean): Shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp)

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
    // Two-bucket key model (mirrors `MediaImageBubble`):
    //   - `decodeKey` includes `sourceEpoch`, scoped to bytes-level state.
    //   - `tileSlot` omits the epoch, scoped to user-choice state
    //     (materialization intent) so a background ref upgrade can't re-gate a tile
    //     the user already consented to fetch.
    val decodeKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    val tileSlot = "$messageIdHex#$attachmentIndex"
    var presentation by remember(decodeKey) {
        val cached =
            if (MediaPipeline.canSeedStaticThumbnailFromMediaType(reference.mediaType)) {
                controller.thumbnailFor(messageIdHex, attachmentIndex)
            } else {
                null
            }
        mutableStateOf<DecodedAttachmentPresentation?>(
            cached?.let { DecodedAttachmentPresentation.Static(it) },
        )
    }
    var failed by remember(decodeKey) { mutableStateOf(false) }
    var reloadToken by remember(decodeKey) { mutableIntStateOf(0) }
    var cachedPlaintextOnEntry by rememberImageAttachmentCacheAvailability(
        controller,
        messageIdHex,
        attachmentIndex,
        reference.sourceEpoch,
        presentation != null,
    )
    val retainedPlaintextOnEntry =
        mine && controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex) != null
    // Mirror the single-image bubble's auto-download gate (#10) so the
    // policy applies to album tiles too. Retained/cached outgoing bytes still
    // materialize during a pause, but a cache-missing network fallback waits
    // for restart or a tap. Tightening policy never abandons accepted work.
    val automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused()
    val automaticNetworkAllowed by rememberUpdatedState(
        shouldMaterializeAttachmentAutomatically(
            mine,
            appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused,
        ),
    )
    val policyAllowsMaterialization =
        shouldMaterializeAttachmentAutomatically(
            mine = mine,
            mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused = automaticDownloadsPaused,
            hasCachedAttachment = cachedPlaintextOnEntry,
            hasRetainedPlaintext = retainedPlaintextOnEntry,
        )
    var materializationIntent by
        rememberAttachmentMaterializationIntent(
            identity = tileSlot,
            policyAllowsMaterialization = policyAllowsMaterialization,
        )
    val startDownload = materializationIntent.shouldMaterialize

    LaunchedEffect(decodeKey, materializationIntent, reloadToken) {
        if (presentation != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        failed = false
        try {
            val data =
                imageAttachmentBytes(
                    controller = controller,
                    messageIdHex = messageIdHex,
                    attachmentIndex = attachmentIndex,
                    reference = reference,
                    mine = mine,
                    priority = materializationIntent.priority,
                    allowNetwork =
                        materializationIntent == AttachmentMaterializationIntent.Interactive || automaticNetworkAllowed,
                ) ?: run {
                    cachedPlaintextOnEntry = false
                    materializationIntent =
                        AttachmentMaterializationIntent.Idle.withPolicyAllowed(automaticNetworkAllowed)
                    // A grant during the local read preserves the intent, so it needs a new effect key.
                    if (automaticNetworkAllowed) reloadToken++
                    return@LaunchedEffect
                }
            val decoded =
                decodeMessageAttachmentImage(
                    bytes = data,
                    mediaType = reference.mediaType,
                    staticMaxEdgePx = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                )
            if (decoded != null) {
                if (decoded is DecodedAttachmentPresentation.Static) {
                    controller.cacheThumbnail(messageIdHex, attachmentIndex, decoded.bitmap)
                }
                presentation = decoded
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            materializationIntent = materializationIntent.afterProducerCancellation(cancel)
        } catch (_: Throwable) {
            android.util.Log.w("MediaImageGridTile", "image_tile_auto_download_failed")
            failed = true
        }
    }

    persistedAttachmentOpenEffect(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        sourceEpoch = reference.sourceEpoch,
        controller = controller,
        appState = appState,
        isReady = { presentation != null },
        ensureMaterialization = {
            if (failed) {
                failed = false
                reloadToken++
            }
            materializationIntent = materializationIntent.afterInteractiveRequest()
        },
        dispatchOpen = { onTap() },
    )

    Box(
        modifier =
            modifier.combinedClickable(
                onLongClick = onLongPress,
                // Two modes:
                //   - Bytes ready (`bitmap != null`): tap opens the viewer.
                //   - Bytes pending: tap persists interactive open intent, so
                //     the promoted transfer opens once after verified decode.
                onClick = {
                    if (presentation != null) {
                        onTap()
                    } else {
                        controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val current = presentation
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
                Image(
                    bitmap = current.toImageBitmap(),
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            is DecodedAttachmentPresentation.Animated ->
                AnimatedDrawableAttachmentImage(
                    drawable = current.drawable,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            null ->
                when {
                    failed ->
                        MediaCircleAction(
                            icon = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.media_tap_to_retry),
                            onClick = {
                                controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
                            },
                        )
                    !startDownload ->
                        MediaCircleAction(
                            icon = Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.media_tap_to_download),
                            onClick = {
                                controller.requestAttachmentOpen(messageIdHex, attachmentIndex)
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
                        .background(Color.Black.copy(alpha = ScrimAlpha.TILE)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
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
