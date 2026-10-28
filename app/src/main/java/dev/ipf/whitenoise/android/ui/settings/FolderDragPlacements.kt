package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateMapOf

/**
 * Actual pre-offset lazy-row positions, normalized to the list's content origin. Pinned offscreen
 * rows collapse uncomposed slots, so neither total consumed scroll nor visibleItemsInfo locates them.
 */
internal class FolderDragPlacements(private val list: LazyListState) {
    private val tops = mutableStateMapOf<String, Float>()
    private val translations = mutableMapOf<String, Float>()

    /** Reads a currently placed row, including a pinned row outside visibleItemsInfo. */
    fun top(id: String): Float? = tops[id]

    /** Normalizes actual placement to the same origin used by visible slots and viewport edge scrolling. */
    fun record(
        id: String,
        top: Float,
    ) {
        tops[id] = top - list.layoutInfo.beforeContentPadding
    }

    /** Captures the drawn displacement, including a sibling animation interrupted between its endpoints. */
    fun recordTranslation(
        id: String,
        translation: Float,
    ) {
        translations[id] = translation
    }

    /** Disposed lazy rows cannot contribute stale coordinates to a later release. */
    fun forget(id: String) {
        tops.remove(id)
        translations.remove(id)
    }

    /** Records every surviving placed row's rendered position before authoritative reordering changes its slot. */
    fun capture(ids: List<String>): Map<String, Float> =
        tops
            .filterKeys { it in ids }
            .mapValues { (key, top) -> top + (translations[key] ?: 0f) }

    /** A finished drag retains current row placement but retires its transient displacement history. */
    fun clearTranslations() {
        translations.clear()
    }
}
