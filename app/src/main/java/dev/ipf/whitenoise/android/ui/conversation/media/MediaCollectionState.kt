package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** The top-bar switch between the collection grid and the single-item preview. */
internal class MediaCollectionToggle(
    val label: String,
    val enabled: Boolean,
    val onToggle: () -> Unit,
)

/**
 * The staging screen's collection mode: whether the grid shows and which stable item keys are selected.
 * Both survive rotation and process recreation through saved state. Bulk edits are handed to [onChange]
 * as a whole new order; without it (preview-only) the grid is unavailable.
 */
@Stable
internal class MediaCollectionState(
    gridModeState: MutableState<Boolean>,
    private val selectedState: MutableState<List<String>>,
    private val onChange: ((StagedCollectionOrder) -> Unit)?,
) {
    /** Whether the grid replaces the pager and filmstrip. */
    var gridMode by gridModeState

    /** The selected item keys, as [mediaPreviewItemKey] spells them. */
    val selected: Set<String>
        get() = selectedState.value.toSet()

    /** Selects or deselects one item. */
    fun toggle(key: String) {
        val current = selectedState.value
        selectedState.value = if (key in current) current - key else current + key
    }

    /** Forgets selected keys that no longer name a staged item. */
    fun retainOnly(keys: Set<String>) {
        if (selectedState.value.any { it !in keys }) selectedState.value = selectedState.value.filter { it in keys }
    }

    /** Moves [keys] one place in [delta]'s direction and publishes the new order. */
    fun move(
        media: List<PendingMediaSlot>,
        documents: List<Uri>,
        keys: Set<String>,
        delta: Int,
    ) {
        onChange?.invoke(reorderStagedCollection(media, documents, keys, delta))
    }

    /** The bulk bar's actions over the current selection. */
    fun actions(
        items: List<StagedPreviewItem>,
        media: List<PendingMediaSlot>,
        documents: List<Uri>,
    ): MediaCollectionActions =
        MediaCollectionActions(
            onSelectAll = { selectedState.value = items.mapIndexed { i, item -> mediaPreviewItemKey(item, i) } },
            onClearSelection = { selectedState.value = emptyList() },
            onMoveEarlier = { move(media, documents, selected, -1) },
            onMoveLater = { move(media, documents, selected, 1) },
            onRemoveSelected = {
                val removed = selected
                selectedState.value = emptyList()
                onChange?.invoke(removeFromStagedCollection(media, documents, removed))
            },
        )

    /** The grid's heading, or null to keep the preview's own. */
    @Composable
    fun title(total: Int): String? {
        val heading = pluralStringResource(R.plurals.media_collection_total, total, total)
        return heading.takeIf { gridMode }
    }

    /** The switch shown when the collection can be edited and holds more than one item. */
    @Composable
    fun gridToggle(
        total: Int,
        enabled: Boolean,
    ): MediaCollectionToggle? {
        if (onChange == null || total < 2) return null
        val label =
            stringResource(if (gridMode) R.string.media_collection_show_single else R.string.media_collection_show_grid)
        return MediaCollectionToggle(label, enabled) { gridMode = !gridMode }
    }
}

/**
 * Remembers the collection mode for [items]: the grid opens by itself once more than
 * [MEDIA_COLLECTION_GRID_THRESHOLD] items are staged, and selections of removed items are dropped.
 */
@Composable
internal fun rememberMediaCollectionState(
    items: List<StagedPreviewItem>,
    onChange: ((StagedCollectionOrder) -> Unit)?,
): MediaCollectionState {
    val gridMode = rememberSaveable { mutableStateOf(onChange != null && items.size > MEDIA_COLLECTION_GRID_THRESHOLD) }
    val selected = rememberSaveable { mutableStateOf(emptyList<String>()) }
    val state = remember(onChange) { MediaCollectionState(gridMode, selected, onChange) }
    LaunchedEffect(items) {
        state.retainOnly(items.mapIndexed { index, item -> mediaPreviewItemKey(item, index) }.toSet())
    }
    return state
}
