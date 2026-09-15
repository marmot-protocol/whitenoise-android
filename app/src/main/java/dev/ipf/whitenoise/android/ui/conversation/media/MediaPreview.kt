@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Full-screen preview of staged media before sending. */
@Composable
@Suppress("LongParameterList")
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
 * Staged attachments in app-theme colors: a pager of frames, a filmstrip and a Done action that applies
 * the frames the user unchecked. Output quality now belongs to the photo editor, so nothing on this
 * screen re-encodes an attachment; [onSelectMediaQuality] and [preparedPhotoQualities] survive only for
 * the existing staging call site. Preview-only mode delegates Send back to the composer.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "UNUSED_PARAMETER", "CyclomaticComplexMethod")
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
    val metadata = rememberPreviewMetadata(items)
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears the staging shelf and this screen leaves composition.
    var sending by remember { mutableStateOf(false) }
    var excluded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var applying by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    val scope = rememberCoroutineScope()
    val preparing = items.any { it is StagedPreviewItem.Media && it.slot.id in preparingPhotoSlotIds }
    val current = items.getOrNull(pagerState.currentPage)
    val currentMedia = current as? StagedPreviewItem.Media
    val currentMetadata = current?.let { metadata[it.uri] }
    val genericEditRefusal = stringResource(R.string.photo_editor_not_editable_source)
    ApplyPreviewExclusions(
        active = applying,
        items = items,
        excluded = excluded.toSet(),
        mediaCount = mediaSlots.size,
        onRemoveMediaAt = onRemoveMediaAt,
        onRemoveDocumentAt = onRemoveDocumentAt,
        onFinished = {
            applying = false
            onClose()
        },
    )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediaPreviewTopBar(
                editAction =
                    currentMedia
                        // The prototype withholds Edit from video and GIF. Unresolved metadata is
                        // not evidence of either, so a still whose probe is still in flight keeps
                        // its action rather than losing it for the life of the screen.
                        ?.takeIf { onEditMediaAt != null && currentMetadata?.isVideo != true }
                        ?.takeIf { currentMetadata?.isGif != true }
                        ?.let { media ->
                            MediaPreviewEditAction(
                                enabled =
                                    !sending &&
                                        !applying &&
                                        !preparing &&
                                        media.slot.id !in nonEditableMediaSlotIds,
                                description =
                                    editActionDescription(
                                        slotId = media.slot.id,
                                        descriptions = nonEditableMediaDescriptions,
                                        nonEditableSlotIds = nonEditableMediaSlotIds,
                                        genericRefusal = genericEditRefusal,
                                    ),
                                onEdit = { onEditMediaAt?.invoke(pagerState.currentPage) },
                            )
                        },
                onClose = onClose,
                onDone = { applying = true },
                doneEnabled = !sending && !applying,
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            MediaPreviewPager(
                items = items,
                pagerState = pagerState,
                metadata = metadata,
                prepared = preparedPhotoPreviews,
                excluded = excluded.toSet(),
                onIncludedChange = { key, included ->
                    excluded = if (included) excluded - key else (excluded + key).distinct()
                },
                onDismiss = onClose,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            if (items.size > 1 || !previewOnly) {
                MediaPreviewFilmstrip(
                    items = items,
                    metadata = metadata,
                    prepared = preparedPhotoPreviews,
                    selectedIndex = pagerState.currentPage,
                    enabled = !sending && !applying,
                    onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    trailingContent =
                        if (previewOnly) {
                            null
                        } else {
                            { AddMoreThumb(!sending, onAddPhotos, onAddDocuments) }
                        },
                )
            }
            if (!previewOnly) {
                MediaPreviewSendBar(
                    initialCaption = initialCaption,
                    sending = sending,
                    sendEnabled = !sending && !preparing && items.isNotEmpty(),
                    onSend = { caption ->
                        sending = true
                        onSend(caption) { accepted -> if (!accepted) sending = false }
                    },
                )
            }
        }
    }
}

/** What the Edit photo action does, and whether the current still is editable at all. */
internal data class MediaPreviewEditAction(
    val enabled: Boolean,
    val description: String?,
    val onEdit: () -> Unit,
)

/**
 * The Edit action's accessibility label, or `null` when the plain action name is the honest one.
 *
 * A caller that knows why a still is off limits supplies that reason in [descriptions]. A caller that only
 * flags the slot in [nonEditableSlotIds] still owes the user a reason, so the refused action falls back to
 * [genericRefusal] instead of announcing itself as "Edit photo" — a label a screen reader cannot tell apart
 * from the working control. Transient refusals (sending, applying, preparing) are not slot-level judgements,
 * so they keep the plain action name.
 */
private fun editActionDescription(
    slotId: String,
    descriptions: Map<String, String>,
    nonEditableSlotIds: Set<String>,
    genericRefusal: String,
): String? = descriptions[slotId] ?: genericRefusal.takeIf { slotId in nonEditableSlotIds }

/**
 * Removes the frames the user unchecked one at a time. The staging callbacks each rewrite the whole
 * list from one snapshot, so a batch has to walk back-to-front across recompositions instead of
 * firing every removal inside a single frame.
 */
@Composable
@Suppress("LongParameterList")
private fun ApplyPreviewExclusions(
    active: Boolean,
    items: List<StagedPreviewItem>,
    excluded: Set<String>,
    mediaCount: Int,
    onRemoveMediaAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(active, items.size) {
        if (!active) return@LaunchedEffect
        val index = items.indices.lastOrNull { mediaPreviewItemKey(items[it], it) in excluded }
        if (index == null) {
            onFinished()
        } else if (items[index] is StagedPreviewItem.Media) {
            onRemoveMediaAt(index)
        } else {
            onRemoveDocumentAt(index - mediaCount)
        }
    }
}

/** Prototype preview chrome: cancel on the left, then Edit photo (stills only) and Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPreviewTopBar(
    editAction: MediaPreviewEditAction?,
    onClose: () -> Unit,
    onDone: () -> Unit,
    doneEnabled: Boolean,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.preview_media)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cancel_media_changes),
                )
            }
        },
        actions = {
            editAction?.let { action ->
                val fallback = stringResource(R.string.photo_editor_edit_action)
                val describe = Modifier.semantics { contentDescription = action.description ?: fallback }
                TextButton(onClick = action.onEdit, enabled = action.enabled, modifier = describe) {
                    Text(stringResource(R.string.photo_editor_title))
                }
            }
            TextButton(onClick = onDone, enabled = doneEnabled) {
                Text(stringResource(R.string.done))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
