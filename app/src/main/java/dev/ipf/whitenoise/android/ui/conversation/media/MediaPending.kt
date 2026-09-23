package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The optimistic tile dims exactly as far as the confirmed album overflow tile does. */
private const val PENDING_OVERFLOW_SCRIM_ALPHA = 0.58f

/** Marks a queued video's play badge, so tests can tell a video tile from a photo one. */
internal const val PENDING_VIDEO_BADGE_TAG = "conversation.pending.video.badge"

/** Marks the explicit video fallback shown when no poster could be decoded. */
internal const val PENDING_VIDEO_FALLBACK_TAG = "conversation.pending.video.fallback"

/** Placeholder for attachments still uploading, with retry on failure. */
@Composable
internal fun MediaPendingPlaceholder(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    onRetry: (() -> Unit)? = null,
    timestampText: String? = null,
    showStatus: Boolean = false,
    status: MessageStatus = MessageStatus.Pending,
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
) {
    val statusLabel = stringResource(if (failed) R.string.media_upload_failed else R.string.media_uploading)
    val statusColor = if (failed) MaterialTheme.colorScheme.error else Color.White

    // Photos, GIFs and videos are all visual media and keep the image bubble's
    // geometry; a video used to be routed to a generic file pill along with
    // everything it was sent beside, which threw away its preview mid-send
    // (#2732). Genuine documents keep their pills, stacked under the visual
    // bubble so the optimistic → confirmed swap matches the post-upload layout
    // (visual grid above, file pills below).
    val visuals = pendingAttachments.filter { it.isPendingVisualMedia }
    val documents = pendingAttachments.filterNot { it.isPendingVisualMedia }
    if (visuals.isEmpty()) {
        PendingFilePills(
            attachments = documents,
            failed = failed,
            statusLabel = statusLabel,
            onRetry = onRetry,
            timestampText = timestampText,
            showStatus = showStatus,
            status = status,
            retention = retention,
            reserveRetentionSpace = reserveRetentionSpace,
        )
        return
    }
    if (documents.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PendingVisualBubble(visuals, failed, statusLabel, statusColor, onRetry)
            PendingFilePills(
                attachments = documents,
                failed = failed,
                statusLabel = statusLabel,
                onRetry = onRetry,
                timestampText = timestampText,
                showStatus = showStatus,
                status = status,
                retention = retention,
                reserveRetentionSpace = reserveRetentionSpace,
            )
        }
        return
    }
    PendingVisualBubble(visuals, failed, statusLabel, statusColor, onRetry)
}

/** Queued documents as the same stacked pills the confirmed bubble ends with; the last owns the footer. */
@Composable
@Suppress("LongParameterList", "FunctionNaming")
private fun PendingFilePills(
    attachments: List<PendingAttachment>,
    failed: Boolean,
    statusLabel: String,
    onRetry: (() -> Unit)?,
    timestampText: String?,
    showStatus: Boolean,
    status: MessageStatus,
    retention: RetentionIndicatorInput?,
    reserveRetentionSpace: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEachIndexed { index, attachment ->
            val ownsFooter = index == attachments.lastIndex
            PendingFilePill(
                fileName = attachment.fileName,
                mediaType = attachment.mediaType,
                sizeBytes = attachment.plaintextBytes.size.toLong(),
                failed = failed,
                statusLabel = statusLabel,
                onRetry = onRetry,
                timestampText = timestampText.takeIf { ownsFooter },
                showStatus = ownsFooter && showStatus,
                status = status,
                retention = retention.takeIf { ownsFooter },
                reserveRetentionSpace = ownsFooter && reserveRetentionSpace,
            )
        }
    }
}

/** The optimistic visual bubble: one tile, or the same count-specific masonry the confirmed album uses. */
@Composable
@Suppress("FunctionNaming")
private fun PendingVisualBubble(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    statusLabel: String,
    statusColor: Color,
    onRetry: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = ConversationRichContentShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pendingAttachments.size <= 1) {
                // Single-image optimistic: same sizing as the confirmed
                // bubble so the optimistic→confirmed swap doesn't reflow
                // the timeline. Source aspect ratio comes from the
                // attachment's own `dim` (set at pick time).
                val attachment = pendingAttachments.firstOrNull()
                val preview = rememberPendingVisualPreview(attachment)
                val ratio = aspectRatioFromDim(attachment?.dim)
                Box(
                    imageBubbleSizing(ratio, sourceShortSideFromDim(attachment?.dim)),
                    contentAlignment = Alignment.Center,
                ) {
                    preview?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = ScrimAlpha.FAINT)))
                    }
                    if (attachment != null && attachment.isPendingVideo) {
                        PendingVideoBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                        if (preview == null) {
                            PendingVideoFallback(
                                attachment,
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                            )
                        }
                    }
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = preview != null,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                    )
                }
            } else {
                PendingVisualAlbum(pendingAttachments, failed, statusLabel, statusColor, onRetry)
            }
        }
    }
}

/**
 * The optimistic album.
 *
 * It routes through the same count-specific masonry the confirmed bubble uses, so the optimistic to
 * confirmed transition is a visual no-op even on the three-tile case. Every tile draws from local
 * bytes with no network, and one status overlay sits across the whole bubble. Four tiles is the cap,
 * so the surplus collapses into the "+N" chip on the fourth, matching the confirmed grid (#527).
 */
@Composable
@Suppress("FunctionNaming")
private fun PendingVisualAlbum(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    statusLabel: String,
    statusColor: Color,
    onRetry: (() -> Unit)?,
) {
    val visible = pendingAttachments.take(MAX_VISIBLE_GALLERY_FRAMES)
    val overflow = (pendingAttachments.size - visible.size).coerceAtLeast(0)
    Box(Modifier.fillMaxWidth()) {
        MasonryImageLayout(visibleCount = visible.size) { index, tileModifier ->
            val showOverflow = index == visible.lastIndex && overflow > 0
            PendingGridTile(
                attachment = visible[index],
                overflowCount = if (showOverflow) overflow else 0,
                modifier = tileModifier,
            )
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = ScrimAlpha.FAINT)))
        PendingStatusOverlay(
            failed = failed,
            hasPreview = true,
            statusLabel = statusLabel,
            statusColor = statusColor,
            onRetry = onRetry,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun PendingStatusOverlay(
    failed: Boolean,
    hasPreview: Boolean,
    statusLabel: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            // Tap target for retry. Without this the user only has the
            // small refresh icon down in the status row, which is easy to
            // miss on a media bubble dominated by a blurred preview.
            if (onRetry != null) {
                MediaCircleAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    onClick = onRetry,
                )
            } else {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = if (hasPreview) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (hasPreview) {
                    statusColor
                } else {
                    if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** One album tile: a photo's own frame, a video's local poster, or that video's explicit fallback. */
@Composable
private fun PendingGridTile(
    attachment: PendingAttachment,
    overflowCount: Int,
    modifier: Modifier = Modifier,
) {
    val preview = rememberPendingVisualPreview(attachment)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (attachment.isPendingVideo) {
            PendingVideoBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            // The overflow tile still needs to say "+N" even for a video whose poster
            // hasn't decoded yet (or never will) -- the fallback label would otherwise
            // sit where that count belongs, and hide it permanently on a decode failure.
            if (preview == null && overflowCount == 0) {
                PendingVideoFallback(attachment, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            }
        }
        if (overflowCount > 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = PENDING_OVERFLOW_SCRIM_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The local preview for one queued visual attachment.
 *
 * A photo decodes its own retained bytes; a video has a poster frame pulled from the same retained
 * bytes instead, so it keeps a recognizable preview from the composer through to the confirmed
 * bubble rather than collapsing into file details (#2732). Null while the work is still running,
 * or when the bytes carry nothing this surface can draw.
 */
@Composable
private fun rememberPendingVisualPreview(attachment: PendingAttachment?): ImageBitmap? {
    val bytes = attachment?.plaintextBytes
    val isVideo = attachment != null && attachment.isPendingVideo
    var bitmap by remember(bytes, isVideo) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bytes, isVideo) {
        bitmap =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    if (isVideo) {
                        pendingVideoPosterFrame(bytes, extractPoster = true).bitmap
                    } else {
                        MediaPipeline.decodeSampledBitmap(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                    }
                }
            }
    }
    // Recycle the multi-MB ARGB buffer on key change and dispose instead of
    // leaving it to the GC. Capture the instance so a key change recycles the
    // previous bitmap, not the replacement.
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

/** The play marker every queued video carries, so a video tile never reads as a photo. */
@Composable
@Suppress("FunctionNaming")
private fun PendingVideoBadge(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_play_arrow),
        contentDescription = stringResource(R.string.reply_media_video),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp).testTag(PENDING_VIDEO_BADGE_TAG),
    )
}

/**
 * What a queued video names when no poster could be decoded from its bytes.
 *
 * It stays a video: the badge above plus this filename and, when the picker recorded them, its
 * dimensions — never the generic document treatment, which would misdescribe what is being sent.
 */
@Composable
@Suppress("FunctionNaming")
private fun PendingVideoFallback(
    attachment: PendingAttachment,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag(PENDING_VIDEO_FALLBACK_TAG),
    ) {
        Text(
            MediaPipeline.safeDisplayName(attachment.fileName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        attachment.dim?.let { dimensions ->
            Text(
                dimensions,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Decode [bytes] to a sampled [ImageBitmap] off the main thread; null while
 *  decoding or when [bytes] is null/undecodable. */
@Composable
private fun rememberSampledBitmap(bytes: ByteArray?): ImageBitmap? {
    var bitmap by remember(bytes) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            }
    }
    // Recycle the multi-MB ARGB buffer on key change and dispose instead of
    // leaving it to the GC, mirroring ViewerPage. Capture the instance so a
    // key change recycles the previous bitmap, not the replacement.
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}
