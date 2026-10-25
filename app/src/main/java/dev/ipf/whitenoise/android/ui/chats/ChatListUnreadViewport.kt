package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/** Explicit filter dismissal restores the head; normal live pin changes retain their existing policy. */
@Composable
@Suppress("FunctionNaming")
internal fun ChatListUnreadTopResetEffect(
    listState: LazyListState,
    datasetKey: ChatListDatasetKey,
    unreadOnly: Boolean,
    searchOpen: Boolean,
    onScrollRequested: () -> Unit = {},
) {
    val previous = remember(listState, datasetKey, searchOpen) { UnreadViewportState(unreadOnly) }
    SideEffect {
        if (previous.unreadOnly && !unreadOnly && !searchOpen) {
            onScrollRequested()
            listState.requestScrollToItem(0)
        }
        previous.unreadOnly = unreadOnly
    }
}

private class UnreadViewportState(
    var unreadOnly: Boolean,
)
