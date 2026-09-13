package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val PREVIEW_STRIP_MAX_EDGE_PX = 256

internal data class LocalPreviewMetadata(
    val isVideo: Boolean,
    val displayName: String?,
    val isGif: Boolean = false,
)

/** Resolve provider-backed MIME types and names once, off the composition thread. */
@Composable
internal fun rememberPreviewMetadata(items: List<StagedPreviewItem>): Map<android.net.Uri, LocalPreviewMetadata> {
    val context = LocalContext.current
    val metadata by
        produceState<Map<android.net.Uri, LocalPreviewMetadata>>(
            initialValue = emptyMap(),
            key1 = items,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    items.associate { item ->
                        val mime = safeGetType(context.contentResolver, item.uri)
                        item.uri to
                            LocalPreviewMetadata(
                                isVideo = mime.startsWith("video/", ignoreCase = true),
                                isGif = mime.equals("image/gif", ignoreCase = true),
                                displayName =
                                    if (item is StagedPreviewItem.Document) {
                                        queryDisplayName(context.contentResolver, item.uri)
                                    } else {
                                        null
                                    },
                            )
                    }
                }
        }
    return metadata
}

internal data class PreparedPhotoPreview(
    val revision: String,
    val bytes: ByteArray,
)

/** Decode the prepared send artifact when available, otherwise the original local Uri. */
@Composable
internal fun rememberMediaPreviewBitmap(
    uri: android.net.Uri,
    isVideo: Boolean,
    maxEdgePx: Int,
    prepared: PreparedPhotoPreview? = null,
): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by
        remember(uri, isVideo, maxEdgePx, prepared?.revision) {
            mutableStateOf<android.graphics.Bitmap?>(null)
        }
    LaunchedEffect(uri, isVideo, maxEdgePx, prepared?.revision) {
        var decoded: android.graphics.Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                decoded =
                    if (prepared != null) {
                        MediaPipeline.decodeSampledBitmap(prepared.bytes, maxEdgePx)
                    } else if (isVideo) {
                        // Video URI: extract the first frame as the staging thumbnail
                        // instead of trying to decode the bytes as JPEG (which spins
                        // forever on a video and leaves the sheet stuck). Scaled to
                        // the staging tile size — full-res posters from a 4K clip
                        // would be a ~33 MB ARGB bitmap per tile.
                        runCatching {
                            val mmr = android.media.MediaMetadataRetriever()
                            try {
                                mmr.setDataSource(context, uri)
                                mmr
                                    .getScaledFrameAtTime(
                                        0L,
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        maxEdgePx,
                                        maxEdgePx,
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
                                    maxEdgePx,
                                )
                        }.getOrNull()
                    }
            }
            currentCoroutineContext().ensureActive()
            bitmap = decoded
            decoded = null
        } finally {
            // A key change can cancel this effect after decoding but before
            // publication. Recycle that orphan immediately instead of waiting
            // for a large native buffer to reach the GC.
            decoded?.recycle()
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

/** One staged attachment in the preview, in send order — media first, then documents. */
internal sealed class StagedPreviewItem {
    abstract val uri: android.net.Uri

    data class Media(
        val slot: PendingMediaSlot,
    ) : StagedPreviewItem() {
        override val uri: android.net.Uri = slot.uri
    }

    data class Document(
        override val uri: android.net.Uri,
    ) : StagedPreviewItem()
}

internal fun stagedPreviewItems(
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<android.net.Uri>,
): List<StagedPreviewItem> = mediaSlots.map { StagedPreviewItem.Media(it) } + documentUris.map { StagedPreviewItem.Document(it) }

/**
 * Where the preview cursor lands after removing [removedIndex] from a list
 * that now holds [remainingCount] items. Removing an item before the cursor
 * shifts it left, removing the cursor itself keeps its slot (the next item
 * slides in), clamped to the new bounds.
 */
internal fun previewIndexAfterRemoval(
    removedIndex: Int,
    currentIndex: Int,
    remainingCount: Int,
): Int =
    when {
        remainingCount <= 0 -> 0
        removedIndex < currentIndex -> (currentIndex - 1).coerceIn(0, remainingCount - 1)
        else -> currentIndex.coerceIn(0, remainingCount - 1)
    }

@Composable
internal fun MediaPreviewScreen(
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<android.net.Uri>,
    chatTitle: String?,
    onDismiss: () -> Unit,
    onSend: (caption: String, onResult: (accepted: Boolean) -> Unit) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
    onEditMediaAt: ((Int) -> Unit)? = null,
    onSelectMediaQuality: ((String, MediaQuality) -> Unit)? = null,
    preparedPhotoPreviews: Map<String, PreparedPhotoPreview> = emptyMap(),
    preparedPhotoQualities: Map<String, PreparedPhotoQuality> = emptyMap(),
    preparingPhotoSlotIds: Set<String> = emptySet(),
    nonEditableMediaSlotIds: Set<String> = emptySet(),
    nonEditableMediaDescriptions: Map<String, String> = emptyMap(),
    initialCaption: String = "",
    previewOnly: Boolean = false,
    initialIndex: Int = 0,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        MediaPreviewContent(
            mediaSlots = mediaSlots,
            documentUris = documentUris,
            chatTitle = chatTitle,
            initialCaption = initialCaption,
            previewOnly = previewOnly,
            initialIndex = initialIndex,
            onClose = onDismiss,
            onSend = onSend,
            onRemoveMediaAt = onRemoveAt,
            onRemoveDocumentAt = onRemoveDocumentAt,
            onAddPhotos = onAddPhotos,
            onAddDocuments = onAddDocuments,
            onEditMediaAt = onEditMediaAt,
            onSelectMediaQuality = onSelectMediaQuality,
            preparedPhotoPreviews = preparedPhotoPreviews,
            preparedPhotoQualities = preparedPhotoQualities,
            preparingPhotoSlotIds = preparingPhotoSlotIds,
            nonEditableMediaSlotIds = nonEditableMediaSlotIds,
            nonEditableMediaDescriptions = nonEditableMediaDescriptions,
        )
    }
}

/**
 * Renders the native stable-slot staging owner in app-theme colors; preview-only mode still delegates Send to the
 * composer.
 */
@Composable
internal fun MediaPreviewContent(
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<android.net.Uri>,
    chatTitle: String?,
    onClose: () -> Unit,
    onSend: (caption: String, onResult: (accepted: Boolean) -> Unit) -> Unit,
    onRemoveMediaAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
    onEditMediaAt: ((Int) -> Unit)? = null,
    onSelectMediaQuality: ((String, MediaQuality) -> Unit)? = null,
    preparedPhotoPreviews: Map<String, PreparedPhotoPreview> = emptyMap(),
    preparedPhotoQualities: Map<String, PreparedPhotoQuality> = emptyMap(),
    preparingPhotoSlotIds: Set<String> = emptySet(),
    nonEditableMediaSlotIds: Set<String> = emptySet(),
    nonEditableMediaDescriptions: Map<String, String> = emptyMap(),
    initialCaption: String = "",
    previewOnly: Boolean = false,
    initialIndex: Int = 0,
) {
    val items = remember(mediaSlots, documentUris) { stagedPreviewItems(mediaSlots, documentUris) }
    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex) }
    // Seeded from the composer draft so text typed before attaching carries
    // into the caption instead of silently waiting behind the send.
    var caption by rememberSaveable { mutableStateOf(initialCaption) }
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears the staging shelf and this screen leaves composition.
    var sending by remember { mutableStateOf(false) }
    val previewMetadata = rememberPreviewMetadata(items)
    val preparingAttachments =
        items.any { item -> item is StagedPreviewItem.Media && item.slot.id in preparingPhotoSlotIds }
    LaunchedEffect(items.size) {
        currentIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun removeAt(index: Int) {
        if (sending || index !in items.indices) return
        val item = items[index]
        currentIndex = previewIndexAfterRemoval(index, currentIndex, items.size - 1)
        when (item) {
            is StagedPreviewItem.Media -> onRemoveMediaAt(index)
            is StagedPreviewItem.Document -> onRemoveDocumentAt(index - mediaSlots.size)
        }
    }

    // Preserve this native staging route inside the same themed shell as the redesigned full viewer.
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
            }
            Text(
                text = chatTitle.orEmpty(),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val currentMedia = items.getOrNull(currentIndex) as? StagedPreviewItem.Media
            val currentMetadata = currentMedia?.let { previewMetadata[it.uri] }
            val currentPreparing = currentMedia?.slot?.id in preparingPhotoSlotIds
            val showQuality = currentMetadata?.isVideo == false && onSelectMediaQuality != null
            if (currentMedia != null && showQuality) {
                PhotoQualitySelector(
                    slotId = currentMedia.slot.id,
                    qualities = preparedPhotoQualities,
                    enabled = !sending && !currentPreparing,
                    onSelect = onSelectMediaQuality,
                    tint = MaterialTheme.colorScheme.onBackground,
                    themedSheet = true,
                )
            }
            if (currentMedia != null && currentMetadata?.isVideo == false && onEditMediaAt != null) {
                val editable = currentMedia.slot.id !in nonEditableMediaSlotIds
                val nonEditableDescription =
                    nonEditableMediaDescriptions[currentMedia.slot.id]
                        ?: stringResource(R.string.photo_editor_not_editable_source)
                val editDescription = stringResource(R.string.photo_editor_edit_action)
                IconButton(
                    onClick = { onEditMediaAt(currentIndex) },
                    enabled = !sending && !currentPreparing && editable,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                if (!editable) {
                                    nonEditableDescription
                                } else {
                                    editDescription
                                }
                        },
                ) {
                    if (currentPreparing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = null)
                    }
                }
            }
            IconButton(
                onClick = { removeAt(currentIndex) },
                enabled = items.isNotEmpty() && !sending,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            ) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.media_attachment_remove),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val item = items.getOrNull(currentIndex)) {
                is StagedPreviewItem.Media ->
                    HeroMediaPreview(
                        uri = item.uri,
                        metadata = previewMetadata[item.uri],
                        prepared = preparedPhotoPreviews[item.slot.id],
                    )
                is StagedPreviewItem.Document -> HeroDocumentPreview(item.uri, previewMetadata[item.uri])
                null -> Unit
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(
                    items,
                    key = { _, item ->
                        when (item) {
                            is StagedPreviewItem.Media -> "image:${item.slot.id}"
                            is StagedPreviewItem.Document -> "doc:${item.uri}"
                        }
                    },
                ) { index, item ->
                    PreviewStripThumb(
                        item = item,
                        metadata = previewMetadata[item.uri],
                        prepared = (item as? StagedPreviewItem.Media)?.let { preparedPhotoPreviews[it.slot.id] },
                        position = index + 1,
                        selected = index == currentIndex,
                        enabled = !sending,
                        onClick = { currentIndex = index },
                    )
                }
                item(key = "media_preview_add_more_tile") {
                    AddMoreThumb(
                        enabled = !sending,
                        onAddPhotos = onAddPhotos,
                        onAddDocuments = onAddDocuments,
                    )
                }
            }
            if (!previewOnly) {
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
                        colors = previewCaptionFieldColors(),
                    )
                    FloatingActionButton(
                        onClick = {
                            if (!sending && !preparingAttachments && items.isNotEmpty()) {
                                sending = true
                                onSend(caption) { accepted ->
                                    if (!accepted) sending = false
                                }
                            }
                        },
                        modifier =
                            Modifier.semantics {
                                if (sending || preparingAttachments || items.isEmpty()) disabled()
                            },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_upward),
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Pairs native caption, cursor and outline colors with the current viewer surface. */
@Composable
private fun previewCaptionFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onBackground,
        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )

@Composable
private fun HeroMediaPreview(
    uri: android.net.Uri,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap =
            metadata?.let {
                rememberMediaPreviewBitmap(uri, it.isVideo, MediaPipeline.THUMBNAIL_MAX_EDGE_PX, prepared)
            }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = prepared?.let { stringResource(R.string.photo_editor_prepared) },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (metadata?.isVideo == true) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.reply_media_video),
                    tint = Color.White,
                    modifier =
                        Modifier
                            .padding(16.dp)
                            .size(40.dp),
                )
            }
        }
    }
}

/** Keeps native document metadata readable against the current themed viewer shell. */
@Composable
private fun HeroDocumentPreview(
    uri: android.net.Uri,
    metadata: LocalPreviewMetadata?,
) {
    val displayName = metadata?.displayName ?: uri.lastPathSegment ?: stringResource(R.string.reply_media_document)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PreviewStripThumb(
    item: StagedPreviewItem,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
    position: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val positionDescription = stringResource(R.string.media_preview_position_badge, position)
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ).selectable(selected = selected, enabled = enabled, onClick = onClick)
                .semantics { contentDescription = positionDescription },
    ) {
        when (item) {
            is StagedPreviewItem.Media -> {
                val bitmap =
                    metadata?.let {
                        rememberMediaPreviewBitmap(item.uri, it.isVideo, PREVIEW_STRIP_MAX_EDGE_PX, prepared)
                    }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = prepared?.let { stringResource(R.string.photo_editor_prepared) },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                if (metadata?.isVideo == true) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE)) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .padding(4.dp)
                                        .size(16.dp),
                            )
                        }
                    }
                }
            }
            is StagedPreviewItem.Document ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$position",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Retains the native photo/document acquisition choices beside the staged send-order thumbnails. */
@Composable
private fun AddMoreThumb(
    enabled: Boolean,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
) {
    // Anchor a DropdownMenu to the tile so the user can add either kind to a
    // mixed shelf — the tile alone can't know which the user wants to append.
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { if (enabled) menuOpen = true },
            enabled = enabled,
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.media_attachment_add_more),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MenuDefaults.shape,
            border = amoledSurfaceBorderStroke(),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_photo_library)) },
                onClick = {
                    menuOpen = false
                    onAddPhotos()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.attach_document)) },
                onClick = {
                    menuOpen = false
                    onAddDocuments()
                },
            )
        }
    }
}
