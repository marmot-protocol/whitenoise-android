package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

/** Stable presentation identity only; unavailable results are never reconstructed from it. */
internal data class GlobalSearchSelectedResult(
    val groupId: String = "",
    val messageId: String = "",
    val attachmentIndex: Int = -1,
) {
    fun matches(
        groupId: String,
        messageId: String,
        attachmentIndex: Int = -1,
    ): Boolean =
        this.groupId.isNotEmpty() &&
            this.messageId.isNotEmpty() &&
            this.groupId.equals(groupId, ignoreCase = true) &&
            this.messageId.equals(messageId, ignoreCase = true) &&
            this.attachmentIndex == attachmentIndex
}

private const val SELECTED_RESULT_FIELD_COUNT = 3

private val GlobalSearchSelectedResultSaver =
    listSaver<GlobalSearchSelectedResult, String>(
        save = { listOf(it.groupId, it.messageId, it.attachmentIndex.toString()) },
        restore = { saved ->
            if (saved.size == SELECTED_RESULT_FIELD_COUNT) {
                GlobalSearchSelectedResult(saved[0], saved[1], saved[2].toIntOrNull() ?: -1)
            } else {
                GlobalSearchSelectedResult()
            }
        },
    )

internal class GlobalSearchSelectionOwner(
    private val state: MutableState<GlobalSearchSelectedResult>,
) {
    var selected by state

    var returnGeneration by mutableLongStateOf(0L)
        private set
    private var consumedFocusGeneration: Long? = null

    fun onConversationReturned() {
        returnGeneration++
    }

    /** A lazy row may remount many times; only the explicit return owns one focus attempt. */
    fun consumeReturnFocus(generation: Long): Boolean {
        if (generation != returnGeneration || consumedFocusGeneration == generation) return false
        consumedFocusGeneration = generation
        return true
    }
}

/** Handles both a remounted route and a fast Back before its outgoing composition is disposed. */
@Composable
internal fun rememberReturnedSearchSelection(owner: GlobalSearchSelectionOwner?): GlobalSearchSelectedResult? =
    remember(owner, owner?.returnGeneration) { owner?.selected }

internal data class GlobalSearchResetOwners(
    val active: ChatListSearchViewportState,
    val archived: ChatListSearchViewportState,
    val grid: ChatListSearchViewportState,
)

/** Shell-lived coordinates and ids only: never retains messages, thumbnails, or native result pages. */
internal class GlobalSearchViewport(
    private val active: LazyListState,
    private val archived: LazyListState,
    val attachmentGrid: LazyGridState,
    private val resetOwners: GlobalSearchResetOwners,
    val selection: GlobalSearchSelectionOwner,
) {
    fun listState(showArchived: Boolean): LazyListState = if (showArchived) archived else active

    fun resetState(showArchived: Boolean): ChatListSearchViewportState {
        if (showArchived) {
            return resetOwners.archived
        }
        return resetOwners.active
    }

    val gridReset: ChatListSearchViewportState get() = resetOwners.grid
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
                viewport.selection.selected = GlobalSearchSelectedResult()
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
    val selectionState =
        rememberSaveable(stateSaver = GlobalSearchSelectedResultSaver) { mutableStateOf(GlobalSearchSelectedResult()) }
    val selection = remember(selectionState) { GlobalSearchSelectionOwner(selectionState) }
    return remember(active, archived, grid, activeReset, archivedReset, gridReset, selection) {
        GlobalSearchViewport(
            active,
            archived,
            grid,
            GlobalSearchResetOwners(activeReset, archivedReset, gridReset),
            selection,
        )
    }
}
