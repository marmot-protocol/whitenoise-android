@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Description

/** Above this many items the staging screen opens on the compact grid instead of the single-item pager. */
internal const val MEDIA_COLLECTION_GRID_THRESHOLD = 10

private const val CELL_DECODE_EDGE_PX = 192
private val CELL_MIN_SIZE = 96.dp
private val CELL_SPACING = 4.dp
private val BADGE_SIZE = 24.dp

/** The staged shelf after a bulk edit, still split into its visual album and its documents. */
internal data class StagedCollectionOrder(
    val media: List<PendingMediaSlot>,
    val documents: List<Uri>,
)

/**
 * Moves every selected key one place earlier ([delta] < 0) or later, as a block: a selected key only trades
 * places with an unselected neighbour, so a run of selected keys keeps its internal order.
 */
internal fun moveSelectedKeys(
    keys: List<String>,
    selected: Set<String>,
    delta: Int,
): List<String> {
    val result = keys.toMutableList()
    val indices = if (delta < 0) 1 until result.size else (result.size - 2 downTo 0)
    val step = if (delta < 0) -1 else 1
    indices.forEach { index ->
        val neighbour = index + step
        if (result[index] in selected && result[neighbour] !in selected) {
            result[neighbour] = result[index].also { result[index] = result[neighbour] }
        }
    }
    return result
}

/** Moves the selected items within their own section; photos and documents are sent as separate messages. */
internal fun reorderStagedCollection(
    media: List<PendingMediaSlot>,
    documents: List<Uri>,
    selectedKeys: Set<String>,
    delta: Int,
): StagedCollectionOrder {
    val items = stagedPreviewItems(media, documents)
    val mediaByKey = items.take(media.size).associateBy { mediaPreviewItemKey(it, 0) }
    val documentByKey = items.drop(media.size).associateBy { mediaPreviewItemKey(it, 0) }
    val mediaOrder = moveSelectedKeys(mediaByKey.keys.toList(), selectedKeys, delta)
    val documentOrder = moveSelectedKeys(documentByKey.keys.toList(), selectedKeys, delta)
    return StagedCollectionOrder(
        media = mediaOrder.map { (mediaByKey.getValue(it) as StagedPreviewItem.Media).slot },
        documents = documentOrder.map { documentByKey.getValue(it).uri },
    )
}

/** Drops every selected item, keeping the rest in order. */
internal fun removeFromStagedCollection(
    media: List<PendingMediaSlot>,
    documents: List<Uri>,
    selectedKeys: Set<String>,
): StagedCollectionOrder {
    val kept = stagedPreviewItems(media, documents).filterNot { mediaPreviewItemKey(it, 0) in selectedKeys }
    return StagedCollectionOrder(
        media = kept.filterIsInstance<StagedPreviewItem.Media>().map { it.slot },
        documents = kept.filterIsInstance<StagedPreviewItem.Document>().map { it.uri },
    )
}

/**
 * A virtualized grid of every staged item for large collections. Only visible cells compose and decode,
 * so 100 mixed items stay responsive. A tap opens the single-item preview, or toggles selection while
 * anything is selected; a long press starts selecting. Each cell announces its position, kind and state,
 * and offers Move earlier and Move later as accessibility actions.
 */
@Composable
@Suppress("LongParameterList")
internal fun MediaCollectionGrid(
    items: List<StagedPreviewItem>,
    metadata: Map<Uri, LocalPreviewMetadata>,
    prepared: Map<String, PreparedPhotoPreview>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (Int) -> Unit,
    onMove: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
) {
    WhiteNoiseLazyVerticalGrid(
        columns = GridCells.Adaptive(CELL_MIN_SIZE),
        state = state,
        modifier = modifier.testTag("conversation.media.collection.grid"),
        contentPadding = PaddingValues(CELL_SPACING),
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        itemsIndexed(items, key = { index, item -> mediaPreviewItemKey(item, index) }) { index, item ->
            val key = mediaPreviewItemKey(item, index)
            MediaCollectionCell(
                item = item,
                position = index + 1,
                total = items.size,
                metadata = metadata[item.uri],
                prepared = (item as? StagedPreviewItem.Media)?.let { prepared[it.slot.id] },
                selected = key in selectedKeys,
                onClick = { if (selectedKeys.isEmpty()) onOpen(index) else onToggle(key) },
                onLongClick = { onToggle(key) },
                onMove = { delta -> onMove(key, delta) },
            )
        }
    }
}

/** One square cell: a thumbnail or file name, its send position, and a check when selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun MediaCollectionCell(
    item: StagedPreviewItem,
    position: Int,
    total: Int,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val name = metadata?.displayName ?: item.uri.lastPathSegment.orEmpty()
    val label =
        when {
            item is StagedPreviewItem.Document ->
                stringResource(R.string.media_collection_item_document, name, position, total)
            metadata?.isVideo == true -> stringResource(R.string.media_collection_item_video, position, total)
            else -> stringResource(R.string.media_collection_item_photo, position, total)
        }
    val state =
        stringResource(if (selected) R.string.media_collection_selected else R.string.media_collection_not_selected)
    val earlier = stringResource(R.string.media_collection_move_earlier)
    val later = stringResource(R.string.media_collection_move_later)
    Box(
        modifier =
            Modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                ).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics {
                    contentDescription = label
                    stateDescription = state
                    this.selected = selected
                    customActions =
                        listOf(
                            CustomAccessibilityAction(earlier) { true.also { onMove(-1) } },
                            CustomAccessibilityAction(later) { true.also { onMove(1) } },
                        )
                }.testTag("conversation.media.collection.cell"),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap =
            (item as? StagedPreviewItem.Media)?.let { media ->
                metadata?.let { rememberMediaPreviewBitmap(media.uri, it.isVideo, CELL_DECODE_EDGE_PX, prepared) }
            }
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                if (item is StagedPreviewItem.Document) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        MediaCollectionBadge(position, selected, Modifier.align(Alignment.TopEnd))
    }
}

/** The send position, replaced by a check while the cell is selected. */
@Composable
private fun MediaCollectionBadge(
    position: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .padding(4.dp)
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(if (selected) colors.primary else colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp), tint = colors.onPrimary)
        } else {
            Text(position.toString(), style = MaterialTheme.typography.labelSmall, color = colors.onSurface)
        }
    }
}

/** What the bulk bar can do with the current selection. */
internal class MediaCollectionActions(
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onMoveEarlier: () -> Unit,
    val onMoveLater: () -> Unit,
    val onRemoveSelected: () -> Unit,
)

/**
 * The selection summary and bulk actions under the grid: `selected / total`, select all, clear selection,
 * move earlier, move later and remove. Actions that need a selection are disabled without one.
 */
@Composable
internal fun MediaCollectionActionBar(
    selectedCount: Int,
    total: Int,
    actions: MediaCollectionActions,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasSelection = enabled && selectedCount > 0
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                stringResource(R.string.media_collection_selected_count, selectedCount, total),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("conversation.media.collection.count"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions.onSelectAll, enabled = enabled && selectedCount < total) {
                    Text(stringResource(R.string.media_collection_select_all))
                }
                TextButton(onClick = actions.onClearSelection, enabled = hasSelection) {
                    Text(stringResource(R.string.media_collection_clear_selection))
                }
                TextButton(onClick = actions.onMoveEarlier, enabled = hasSelection) {
                    Text(stringResource(R.string.media_collection_move_earlier))
                }
                TextButton(onClick = actions.onMoveLater, enabled = hasSelection) {
                    Text(stringResource(R.string.media_collection_move_later))
                }
                TextButton(onClick = actions.onRemoveSelected, enabled = hasSelection) {
                    Text(stringResource(R.string.media_collection_remove_selected))
                }
            }
        }
    }
}
