package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.common.LocalAvatarLoadsAllowed

private val CHAT_LIST_COMPOSITION_AHEAD = 900.dp
private val CHAT_LIST_COMPOSITION_BEHIND = 450.dp
private const val CHAT_LIST_ROW_CONTENT_TYPE = "chat-row"

/** Let Compose reuse measured row slots near the viewport, starting on scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberChatAvatarListState(): LazyListState =
    rememberLazyListState(
        cacheWindow =
            remember {
                LazyLayoutCacheWindow(
                    ahead = CHAT_LIST_COMPOSITION_AHEAD,
                    behind = CHAT_LIST_COMPOSITION_BEHIND,
                    isNonScrollCachingEnabled = false,
                )
            },
    )

@Composable
internal fun rememberChatAvatarVisibleKeys(listState: LazyListState): State<Set<Any>> =
    remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key } }
    }

/** One lazy-list interval per section, with the existing stable keys and full-list motion indices. */
internal fun LazyListScope.chatListRows(
    rows: List<ChatListItem>,
    key: (ChatListItem) -> String,
    visibleKeys: State<Set<Any>>,
    indexOffset: Int = 0,
    content: @Composable LazyItemScope.(ChatListItem, Int) -> Unit,
) {
    itemsIndexed(
        rows,
        key = { _, row -> key(row) },
        contentType = { _, _ -> CHAT_LIST_ROW_CONTENT_TYPE },
    ) { index, row ->
        val rowKey = key(row)
        val visible = remember(visibleKeys, rowKey) { derivedStateOf { rowKey in visibleKeys.value } }
        CompositionLocalProvider(LocalAvatarLoadsAllowed provides visible.value) {
            content(row, indexOffset + index)
        }
    }
}

internal fun LazyListScope.chatListRowsWithPinnedBoundary(
    rows: List<ChatListItem>,
    pinnedBoundary: Int?,
    placementDurationMillis: Int,
    visibleKeys: State<Set<Any>>,
    content: @Composable LazyItemScope.(ChatListItem, Int) -> Unit,
) {
    val boundary = pinnedBoundary?.takeIf { it in rows.indices }
    if (boundary == null) {
        chatListRows(rows, ChatListItem::id, visibleKeys, content = content)
        return
    }
    chatListRows(rows.subList(0, boundary), ChatListItem::id, visibleKeys, content = content)
    chatListPinnedBoundaryItem(placementDurationMillis)
    chatListRows(rows.subList(boundary, rows.size), ChatListItem::id, visibleKeys, boundary, content)
}
