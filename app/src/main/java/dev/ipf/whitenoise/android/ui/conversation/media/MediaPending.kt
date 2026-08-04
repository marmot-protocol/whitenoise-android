package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaPendingPlaceholder(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    onRetry: (() -> Unit)? = null,
    attachedToCaption: Boolean = false,
) {
    val statusLabel = stringResource(if (failed) R.string.media_upload_failed else R.string.media_uploading)
    val statusColor = if (failed) MaterialTheme.colorScheme.error else Color.White

    // Image-only sends keep the fixed-height image bubble. The moment a
    // non-image attachment is part of the album the bubble shape switches to
    // a stack of file-pill placeholders so the optimistic → confirmed swap
    // matches the post-upload layout (image grid above, file pills below).
    val allImages = pendingAttachments.isNotEmpty() && pendingAttachments.all { isImagePendingAttachment(it) }
    if (!allImages) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            pendingAttachments.forEach { attachment ->
                PendingFilePill(
                    fileName = attachment.fileName,
                    mediaType = attachment.mediaType,
                    sizeBytes = attachment.plaintextBytes.size.toLong(),
                    failed = failed,
                    statusLabel = statusLabel,
                    onRetry = onRetry,
                    attachedToCaption = attachedToCaption,
                )
            }
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = if (attachedToCaption) RectangleShape else RoundedCornerShape(12.dp),
        border = if (attachedToCaption) null else amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pendingAttachments.size <= 1) {
                // Single-image optimistic: same sizing as the confirmed
                // bubble so the optimistic→confirmed swap doesn't reflow
                // the timeline. Source aspect ratio comes from the
                // attachment's own `dim` (set at pick time).
                val attachment = pendingAttachments.firstOrNull()
                val preview = rememberSampledBitmap(attachment?.plaintextBytes)
                val ratio = aspectRatioFromDim(attachment?.dim)
                Box(
                    imageBubbleSizing(ratio),
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
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = preview != null,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                    )
                }
            } else {
                // Album: route through the same count-specific masonry
                // layout the confirmed bubble uses so the optimistic →
                // confirmed transition is a visual no-op even on the
                // 3-image case. Each tile decodes from local bytes (no
                // network), and a single status overlay sits across the
                // whole bubble. Cap at four tiles so the surplus collapses
                // into the "+N" chip on the fourth tile, matching the
                // confirmed grid bubbles (MasonryImageLayout renders four
                // tiles max) (#527).
                val visible = pendingAttachments.take(4)
                val overflow = (pendingAttachments.size - visible.size).coerceAtLeast(0)
                Box(Modifier.fillMaxWidth()) {
                    MasonryImageLayout(visibleCount = visible.size) { index, tileModifier ->
                        val attachment = visible[index]
                        val showOverflow = index == visible.lastIndex && overflow > 0
                        PendingGridTile(
                            bytes = attachment.plaintextBytes,
                            overflowCount = if (showOverflow) overflow else 0,
                            modifier = tileModifier,
                        )
                    }
                    Box(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = ScrimAlpha.FAINT)),
                    )
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
        }
    }
}

private fun isImagePendingAttachment(attachment: PendingAttachment): Boolean = attachment.mediaType.startsWith("image/", ignoreCase = true)

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

@Composable
private fun PendingGridTile(
    bytes: ByteArray,
    overflowCount: Int,
    modifier: Modifier = Modifier,
) {
    val preview = rememberSampledBitmap(bytes)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (overflowCount > 0 && preview != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha.TILE)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
