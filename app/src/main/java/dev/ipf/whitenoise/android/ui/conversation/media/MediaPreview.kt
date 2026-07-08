package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode a downscaled preview bitmap for a local content Uri, off-thread. */
@Composable
private fun rememberLocalPreviewBitmap(uri: android.net.Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            withContext(Dispatchers.Default) {
                val mime = safeGetType(context.contentResolver, uri)
                if (mime.startsWith("video/", ignoreCase = true)) {
                    // Video URI: extract the first frame as the staging thumbnail
                    // instead of trying to decode the bytes as JPEG (which spins
                    // forever on a video and leaves the sheet stuck). Scaled to
                    // the staging tile size — full-res posters from a 4K clip
                    // would be a ~33 MB ARGB bitmap per tile.
                    runCatching {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(context, uri)
                            val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                            mmr
                                .getScaledFrameAtTime(
                                    0L,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    edge,
                                    edge,
                                )
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }.getOrNull()
                } else {
                    // Decode the picked image straight to a sampled bitmap,
                    // preserving its native format and alpha. Earlier this
                    // round-tripped through MediaPipeline.readDownscaledJpeg
                    // (recompress to JPEG) and then re-decoded those bytes at
                    // full resolution — that flattened transparent PNGs onto
                    // white and, on large lossless sources (e.g. PNG
                    // screenshots), the recompress or the un-sampled re-decode
                    // could silently OOM/fail, leaving the staging tile stuck
                    // on a spinner that never resolved (#387). Mirrors the
                    // in-bubble thumbnail path (decodeSampledBitmap).
                    runCatching {
                        MediaPipeline
                            .decodeSampledFromUri(
                                context.contentResolver,
                                uri,
                                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            )
                    }.getOrNull()
                }
            }
    }
    // Recycle the decoded buffer on key change and dispose instead of leaving
    // it to the GC, mirroring rememberSampledBitmap. Capture the instance so a
    // key change recycles the previous bitmap, not the replacement.
    DisposableEffect(bitmap) {
        val decoded = bitmap
        onDispose { decoded?.recycle() }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

@Composable
private fun LocalImagePreview(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberLocalPreviewBitmap(uri)
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagingTile(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f),
    ) {
        content()
        FilledIconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(40.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.media_attachment_remove),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun StagingDocumentTile(uri: android.net.Uri) {
    val context = LocalContext.current
    val displayName =
        remember(uri) { queryDisplayName(context.contentResolver, uri) ?: "file" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPreviewSheet(
    uris: List<android.net.Uri>,
    documentUris: List<android.net.Uri>,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears pendingMediaUris and the sheet leaves composition.
    var sending by remember { mutableStateOf(false) }
    var addMoreMenuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Horizontally-scrollable shelf of square tiles, one per staged
            // attachment plus a trailing "Add more" tile. Each tile carries a
            // small `✕` overlay that removes only that item from the queue.
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(uris, key = { _, uri -> "image:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveAt(index) },
                    ) {
                        LocalImagePreview(
                            uri = uri,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                itemsIndexed(documentUris, key = { _, uri -> "doc:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveDocumentAt(index) },
                    ) {
                        StagingDocumentTile(uri = uri)
                    }
                }
                item(key = "media_preview_add_more_tile") {
                    // Anchor a DropdownMenu to the tile so the user can add
                    // either kind to a mixed shelf — the tile alone can't
                    // know which (images vs files) the user wants to append.
                    Box {
                        OutlinedButton(
                            onClick = { if (!sending) addMoreMenuOpen = true },
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !sending,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.media_attachment_add_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = addMoreMenuOpen,
                            onDismissRequest = { addMoreMenuOpen = false },
                            shape = MenuDefaults.shape,
                            border = amoledSurfaceBorderStroke(),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddDocuments()
                                },
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.add_caption)) },
                    maxLines = 4,
                    enabled = !sending,
                )
                FilledIconButton(
                    onClick = {
                        if (sending) return@FilledIconButton
                        sending = true
                        onSend(caption)
                    },
                    enabled = !sending,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
