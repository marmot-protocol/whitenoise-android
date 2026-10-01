package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Rows from either end of the retained window at which the next MarmotKit page is requested. */
internal const val CHAT_LIST_WINDOW_PREFETCH_ROWS = 10

/** Whether the retained front is close enough to request the rows that precede it. */
internal fun shouldPageChatListBackward(
    firstVisibleIndex: Int,
    hasMoreBefore: Boolean,
    searchActive: Boolean,
    prefetchRows: Int,
): Boolean =
    !searchActive &&
        hasMoreBefore &&
        firstVisibleIndex >= 0 &&
        firstVisibleIndex < prefetchRows

/** Whether the reader is close enough to the retained end to request the rows that follow it. */
internal fun shouldPageChatListForward(
    lastVisibleIndex: Int,
    totalItems: Int,
    prefetchRows: Int,
): Boolean = totalItems > 0 && lastVisibleIndex >= totalItems - prefetchRows

/** Whether the jump-to-top control must remain available for a shifted bounded window. */
internal fun shouldShowChatListJumpToTop(
    firstVisibleIndex: Int,
    hasMoreBefore: Boolean,
    wasVisible: Boolean,
    showIndex: Int,
    hideIndex: Int,
): Boolean =
    when {
        hasMoreBefore -> true
        firstVisibleIndex >= showIndex -> true
        firstVisibleIndex <= hideIndex -> false
        else -> wasVisible
    }

/**
 * Requests the next forward page whenever the reader is within [prefetchRows] of the retained end.
 *
 * Edge demand is re-evaluated on every viewport change and on every installed window replacement
 * ([windowRevision]), not only when the visible index or item count changes: a page that MDK answers
 * with an unchanged window, or a settled-anchor report that merely moves the cursor, leaves the lazy
 * list identical, and without the revision term the reader parked at the end would never page again
 * (#2926). The window itself bounds re-issue, so a no-progress page cannot busy-loop here.
 */
internal suspend fun collectChatListForwardPaging(
    listState: LazyListState,
    windowRevision: () -> Long,
    prefetchRows: Int = CHAT_LIST_WINDOW_PREFETCH_ROWS,
    onPageForward: suspend () -> Unit,
) {
    snapshotFlow {
        val info = listState.layoutInfo
        Triple(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, windowRevision())
    }.distinctUntilChanged()
        .collect { (lastVisibleIndex, totalItems, _) ->
            if (shouldPageChatListForward(lastVisibleIndex, totalItems, prefetchRows)) onPageForward()
        }
}

/**
 * Reports the first settled visible chat row, by item key, so later replacements keep it in place.
 *
 * The list also holds the inline load-error row, the pinned boundary and search headers, so the
 * settled row is resolved by its item key rather than by index: [chatRowKey] returns the key only
 * for a real chat row. Search rows are a filtered projection of the window and are never reported.
 */
internal suspend fun collectChatListVisibleAnchor(
    listState: LazyListState,
    searchActive: () -> Boolean,
    chatRowKey: (Any) -> String?,
    onVisibleAnchor: suspend (String) -> Unit,
) {
    snapshotFlow {
        val settledRowId = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { chatRowKey(it.key) }
        Triple(listState.isScrollInProgress, searchActive(), settledRowId)
    }.filter { (scrolling, searching, _) -> !scrolling && !searching }
        .map { (_, _, rowId) -> rowId }
        .filterNotNull()
        .distinctUntilChanged()
        .collect { rowId -> onVisibleAnchor(rowId) }
}
