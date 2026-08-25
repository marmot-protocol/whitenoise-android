package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

private class ChatListSearchViewportState(
    var appliedDatasetKey: ChatListDatasetKey? = null,
)

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
    onScrollRequested: () -> Unit = {},
) {
    val viewportState = remember(listState) { ChatListSearchViewportState() }
    SideEffect {
        val activeDatasetKey = datasetKey.takeIf { searchActive }
        if (activeDatasetKey != null && activeDatasetKey != viewportState.appliedDatasetKey) {
            onScrollRequested()
            listState.requestScrollToItem(0)
        }
        viewportState.appliedDatasetKey = activeDatasetKey
    }
}
