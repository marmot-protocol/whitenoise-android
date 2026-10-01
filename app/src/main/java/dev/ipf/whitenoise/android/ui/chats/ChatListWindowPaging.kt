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

/** The laid-out forward edge: the last visible row's index and the number of rows the list measured. */
internal data class ChatListForwardEdge(
    val lastVisibleIndex: Int,
    val totalItems: Int,
) {
    /**
     * Whether this layout shows the rows a page issued at [paged] brought in: a shifted window moves the
     * retained row the reader was on, and so the last visible index, up the list, and a grown window
     * raises the item count. A layout that only scrolled further down is still the old rows.
     */
    fun consumes(paged: ChatListForwardEdge): Boolean =
        when {
            lastVisibleIndex < paged.lastVisibleIndex -> true
            else -> totalItems > paged.totalItems
        }
}

/**
 * Requests the next forward page whenever the reader is within [prefetchRows] of the retained end,
 * and at most one page ahead of what the lazy list has laid out.
 *
 * Installed rows reach the list a frame or two after the page lands (the controller coalesces its
 * projection rebuild), and during that lag the layout still describes the previous rows. Demand
 * evaluated against that layout, whether because a replacement bumped a revision or because the
 * reader scrolled another row, chains further pages against rows the reader has not reached. At the
 * 200-row cap each chained page shifts the window another 50 rows, and once the first visible key
 * has moved beyond the lazy list's key-retention range the viewport falls back to its old index and
 * skips every row in between. So after [onPageForward] reports an installed page, demand waits for a
 * layout that [ChatListForwardEdge.consumes] it. A page that MDK answers with unchanged rows parks
 * forward demand in the window set, and the settled anchor report completes that page there (#2926).
 */
internal suspend fun collectChatListForwardPaging(
    listState: LazyListState,
    prefetchRows: Int = CHAT_LIST_WINDOW_PREFETCH_ROWS,
    onPageForward: suspend () -> Boolean,
) {
    var unconsumed: ChatListForwardEdge? = null
    snapshotFlow {
        val info = listState.layoutInfo
        ChatListForwardEdge(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount)
    }.distinctUntilChanged()
        .collect { edge ->
            unconsumed = unconsumed?.takeUnless(edge::consumes)
            if (unconsumed != null) return@collect
            if (!shouldPageChatListForward(edge.lastVisibleIndex, edge.totalItems, prefetchRows)) return@collect
            if (onPageForward()) unconsumed = edge
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
