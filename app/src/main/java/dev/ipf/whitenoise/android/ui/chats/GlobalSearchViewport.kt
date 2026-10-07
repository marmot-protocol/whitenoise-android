package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection

/** Stable query identity excludes presentation labels and asynchronous result publication. */
internal data class GlobalSearchViewportFilters(
    val folders: Set<String>,
    val chatTypes: Set<GlobalSearchChatType>,
    val chats: Set<String>,
    val senders: Set<String>,
    val date: GlobalSearchDateFilterSelection,
    val content: GlobalSearchContentFilterSelection,
)

internal fun GlobalSearchState.viewportFilters(): GlobalSearchViewportFilters =
    GlobalSearchViewportFilters(
        folders = folderFilters,
        chatTypes = chatTypeFilters,
        chats = chatFilters.mapTo(mutableSetOf()) { it.stableId },
        senders = senderFilters.mapTo(mutableSetOf()) { it.stableId },
        date = dateFilterSelection,
        content = contentFilterSelection,
    )

/** Shell-lived coordinates only: never retains messages, thumbnails, or native result pages. */
internal class GlobalSearchViewport(
    private val active: LazyListState,
    private val archived: LazyListState,
    val attachmentGrid: LazyGridState,
    private val activeReset: ChatListSearchViewportState,
    private val archivedReset: ChatListSearchViewportState,
    val gridReset: ChatListSearchViewportState,
) {
    fun listState(showArchived: Boolean): LazyListState = if (showArchived) archived else active

    fun resetState(showArchived: Boolean): ChatListSearchViewportState = if (showArchived) archivedReset else activeReset
}

/** A new attachment dataset resets once; returning to the same dataset does not. */
@Composable
@Suppress("FunctionNaming")
internal fun GlobalSearchGridResetEffect(
    viewport: GlobalSearchViewport?,
    datasetKey: ChatListDatasetKey,
    browsingAttachments: Boolean,
) {
    SideEffect {
        if (viewport != null) {
            val nextKey = datasetKey.takeIf { browsingAttachments }
            if (nextKey != null && nextKey != viewport.gridReset.appliedDatasetKey) {
                viewport.attachmentGrid.requestScrollToItem(0)
            }
            viewport.gridReset.appliedDatasetKey = nextKey
        }
    }
}

/** Called inside an account/runtime key at the shell, not inside the disposable search route. */
@Composable
internal fun rememberGlobalSearchViewport(): GlobalSearchViewport {
    val active = rememberLazyListState()
    val archived = rememberLazyListState()
    val grid = rememberLazyGridState()
    val activeReset = rememberSaveable(saver = ChatListSearchViewportStateSaver) { ChatListSearchViewportState() }
    val archivedReset = rememberSaveable(saver = ChatListSearchViewportStateSaver) { ChatListSearchViewportState() }
    val gridReset = rememberSaveable(saver = ChatListSearchViewportStateSaver) { ChatListSearchViewportState() }
    return remember(active, archived, grid, activeReset, archivedReset, gridReset) {
        GlobalSearchViewport(active, archived, grid, activeReset, archivedReset, gridReset)
    }
}
