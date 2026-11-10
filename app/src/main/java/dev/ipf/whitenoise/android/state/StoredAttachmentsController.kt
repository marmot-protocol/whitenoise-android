package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/** One largest-first attachment list, across the account or within one chat, and the user's selection. */
internal data class StoredAttachmentsUiState(
    val items: List<StoredAttachment> = emptyList(),
    val complete: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    /** The selected attachments, in list order. */
    val selection: List<StoredAttachment> get() = items.filter { it.key in selected }

    /** Total bytes of the selection, shown while selecting. */
    val selectedBytes: Long get() = selection.sumOf { it.bytes }

    /** True once the user has selected anything. */
    val selecting: Boolean get() = selected.isNotEmpty()
}

/**
 * Pages one attachment list for a screen on the Main dispatcher. [groupIdHex] null lists the whole account.
 * Selection keeps only keys still listed, so a removal or reload never leaves a ghost selected.
 */
internal class StoredAttachmentsController(
    private val access: ManageStorageAccess?,
    private val scope: CoroutineScope,
    private val groupIdHex: String?,
) : Closeable {
    private val mutable = MutableStateFlow(StoredAttachmentsUiState())
    val state = mutable.asStateFlow()
    private var closed = false
    private var cursor: StorageCursor? = null
    private var job: Job? = null

    private val current: Boolean get() = !closed && access?.isCurrent() == true

    /** Starts the list over from its largest attachment. */
    fun reload() {
        if (!current) return
        job?.cancel()
        replaceCursor(null)
        mutable.value = state.value.copy(items = emptyList(), complete = false, failed = false)
        job = scope.launch { loadPage() }
    }

    /** Loads the next page when one exists and none is loading. */
    fun loadMore() {
        val value = state.value
        val idle = !value.loading && !value.complete
        if (!current || !idle || cursor == null) return
        job = scope.launch { loadPage() }
    }

    /** Adds or removes one attachment from the selection. */
    fun toggle(key: String) {
        val selected = state.value.selected
        mutable.value = state.value.copy(selected = if (key in selected) selected - key else selected + key)
    }

    /** Selects every loaded attachment, or clears the selection when [all] is false. */
    fun selectAll(all: Boolean) {
        val keys = if (all) state.value.items.mapTo(HashSet()) { it.key } else emptySet()
        mutable.value = state.value.copy(selected = keys)
    }

    /** Loads the page after the cursor, appending new rows and keeping only selected keys still listed. */
    private suspend fun loadPage() {
        if (!current) return
        mutable.value = state.value.copy(loading = true)
        val page = runCatchingAction { requireNotNull(access).attachments(groupIdHex, cursor) }
        if (!current) {
            page?.next?.close()
            return
        }
        // A failed page keeps its cursor, so Try again resumes from the same place.
        if (page != null) replaceCursor(page.next)
        val known = state.value.items.mapTo(HashSet()) { it.key }
        val items = state.value.items + page?.items.orEmpty().filter { known.add(it.key) }
        mutable.value =
            state.value.copy(
                items = items,
                complete = page != null && page.next == null,
                loading = false,
                failed = page == null,
                selected = state.value.selected intersect items.mapTo(HashSet()) { it.key },
            )
    }

    /** Closes the held cursor and keeps [next] in its place. */
    private fun replaceCursor(next: StorageCursor?) {
        cursor?.close()
        cursor = next
    }

    /** Releases the cursor and stops loading. */
    override fun close() {
        closed = true
        job?.cancel()
        replaceCursor(null)
    }
}
