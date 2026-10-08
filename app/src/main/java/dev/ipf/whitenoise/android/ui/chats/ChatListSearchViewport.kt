package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection

private const val VIEWPORT_SAVED_FIELD_COUNT = 8
private const val VIEWPORT_FILTERS_PRESENT_INDEX = 6
private const val VIEWPORT_FILTERS_STATE_INDEX = 7

/** Save coordinates' query identity, not result bodies, so rotation does not replay an already-applied reset. */
internal val ChatListSearchViewportStateSaver =
    listSaver<ChatListSearchViewportState, Any>(
        save = { owner ->
            val key = owner.appliedDatasetKey
            val filters = key?.searchFilters
            val state =
                GlobalSearchState(
                    folderFilters = filters?.folders.orEmpty(),
                    chatTypeFilters = filters?.chatTypes.orEmpty(),
                    chatFilters = filters?.chats.orEmpty().mapTo(mutableSetOf()) { GlobalSearchChatFilter(it, "") },
                    senderFilters =
                        filters?.senders.orEmpty().mapTo(mutableSetOf()) {
                            GlobalSearchSenderFilter(it, "")
                        },
                    dateFilterSelection = filters?.date ?: GlobalSearchDateFilterSelection.AnyTime,
                    contentFilterSelection = filters?.content ?: GlobalSearchContentFilterSelection.EMPTY,
                )
            listOf(
                key != null,
                key?.showArchived ?: false,
                key?.folderId.orEmpty(),
                key?.query.orEmpty(),
                key?.accountRef.orEmpty(),
                key?.runtimeGeneration ?: 0,
                filters != null,
                encodeGlobalSearchState(state),
            )
        },
        restore = { saved ->
            ChatListSearchViewportState(restoreViewportDataset(saved))
        },
    )

/** An invalid saved coordinate identity gets a fresh reset, never a restoration crash. */
private fun restoreViewportDataset(saved: List<Any>): ChatListDatasetKey? =
    runCatching {
        require(saved.size == VIEWPORT_SAVED_FIELD_COUNT)
        if (saved[0] as Boolean) {
            ChatListDatasetKey(
                showArchived = saved[1] as Boolean,
                folderId = (saved[2] as String).ifEmpty { null },
                query = saved[3] as String,
                accountRef = (saved[4] as String).ifEmpty { null },
                runtimeGeneration = saved[5] as Int,
                searchFilters =
                    if (saved[VIEWPORT_FILTERS_PRESENT_INDEX] as Boolean) {
                        decodeGlobalSearchState(saved[VIEWPORT_FILTERS_STATE_INDEX] as String).viewportFilters()
                    } else {
                        null
                    },
            )
        } else {
            null
        }
    }.getOrNull()

/**
 * Gives a new search dataset one top-reset and no authority over later result
 * publications. Body-result completion is deliberately absent from
 * [datasetKey], so delayed Messages rows preserve a user-owned keyed anchor.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ChatListSearchTopResetEffect(
    listState: LazyListState,
    datasetKey: ChatListDatasetKey,
    searchActive: Boolean,
    viewportState: ChatListSearchViewportState? = null,
    onScrollRequested: () -> Unit = {},
) {
    val owner = viewportState ?: remember(listState) { ChatListSearchViewportState() }
    SideEffect {
        val activeDatasetKey = datasetKey.takeIf { searchActive }
        if (activeDatasetKey != null && activeDatasetKey != owner.appliedDatasetKey) {
            onScrollRequested()
            listState.requestScrollToItem(0)
        }
        owner.appliedDatasetKey = activeDatasetKey
    }
}
