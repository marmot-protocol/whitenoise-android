package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationPagingTraceSection
import dev.ipf.whitenoise.android.state.markPagingEvent

/**
 * The message a conversation list item key stands for, or null when the row is not an authoritative
 * message.
 *
 * Keys are the projected item ids: `msg:<hex>` for an ordinary row and `stream:<id>` for an agent
 * stream. Only a real message id may be reported to MDK as the window anchor — an optimistic row
 * carries a local id the engine never issued, and headers and dividers carry no message at all.
 */
internal fun conversationAnchorMessageId(key: Any?): String? =
    (key as? String)
        ?.let { id -> id.removePrefix(MESSAGE_KEY_PREFIX).takeIf { it != id } }
        ?.takeIf(::isMessageIdHex)

/** Whether [value] is a hex-encoded message id of the length MDK issues. */
private fun isMessageIdHex(value: String): Boolean {
    val sized = value.length == MESSAGE_ID_HEX_LENGTH
    return sized && value.all { char -> char in HEX_DIGITS }
}

/**
 * Whether the reader is close enough to the oldest loaded row to fetch the page behind it.
 *
 * [olderPageBlocked] holds the prefetch off after a page the engine never answered, so the retry is
 * the reader's to make rather than something the effect re-issues on every scroll frame.
 */
internal fun shouldPrefetchOlder(
    anchored: Boolean,
    hasMoreBefore: Boolean,
    isLoadingOlder: Boolean,
    olderPageBlocked: Boolean,
    oldestVisibleIndex: Int,
    oldestMessageListIndex: Int,
): Boolean {
    val canPage = anchored && hasMoreBefore
    val idle = !isLoadingOlder && !olderPageBlocked
    val withinMargin =
        oldestVisibleIndex >= 0 &&
            oldestVisibleIndex >= oldestMessageListIndex - OLDER_PAGE_PREFETCH_ROWS
    return canPage && idle && withinMargin
}

/**
 * Whether the reader is close enough to the newest loaded row to fetch the page ahead of it.
 *
 * [newerPrefetchBlocked] is the forward counterpart of the older-page block (#2764): a send lands
 * the viewport on the newest edge, so a forward page the engine cannot answer would otherwise be
 * asked for again on every layout pass. The block releases as soon as any forward page advances,
 * and it never stands in the way of a deliberate newer-page navigation or retry.
 */
internal fun shouldPrefetchNewer(
    anchored: Boolean,
    hasMoreAfter: Boolean,
    isLoadingOlder: Boolean,
    newerPrefetchBlocked: Boolean,
    newestVisibleIndex: Int,
    newestEdgeIndex: Int,
): Boolean {
    val canPage = anchored && hasMoreAfter
    val idle = !isLoadingOlder && !newerPrefetchBlocked
    // Reversed list: the newest edge is the low-index end, so the lowest visible row approaches it.
    val withinMargin = newestVisibleIndex <= newestEdgeIndex + NEWER_PAGE_PREFETCH_ROWS - 1
    return canPage && idle && withinMargin
}

/**
 * Rows still between the reader's oldest visible row and the row that was the window's edge when the
 * page was asked for. Positive means the page landed before the reader could see the edge; zero or
 * negative means the edge row was already on screen, so the reader saw history arrive.
 */
internal fun olderPageRunwayRows(
    oldestVisibleIndex: Int,
    edgeListIndex: Int,
): Int = edgeListIndex - oldestVisibleIndex

/** The slice a landed page is counted under, from how much runway the reader still had. */
internal fun pageLandingEvent(runwayRows: Int): String =
    if (runwayRows > 0) {
        ConversationPagingTraceSection.RUNWAY_KEPT
    } else {
        ConversationPagingTraceSection.EDGE_REACHED
    }

/**
 * Counts each time the list comes to rest on its oldest row while more history exists and no page is
 * on the way — the one state in which a reader can tell paging is happening. Entry is counted once;
 * layout passes that keep the reader parked there are not.
 */
internal class PagingEdgeStopTracker {
    private var stopped = false

    /** Returns true on the transition into a stop, so the caller records it exactly once. */
    fun observe(
        oldestVisibleIndex: Int,
        edgeListIndex: Int,
        hasMoreBefore: Boolean,
        scrolling: Boolean,
    ): Boolean {
        val atEdge = oldestVisibleIndex >= 0 && oldestVisibleIndex >= edgeListIndex
        val stoppedNow = atEdge && hasMoreBefore && !scrolling
        val entered = stoppedNow && !stopped
        stopped = stoppedNow
        return entered
    }
}

/**
 * Counts how a landed older page found the reader: with rows still to go before the row that was the
 * edge when the page was requested, or already on it. Nothing is counted when the window did not grow,
 * so a no-progress or failed page leaves the landing counters alone.
 */
internal fun recordOlderPageLanding(
    controller: ConversationController,
    listState: LazyListState,
    edgeItemKey: String?,
    renderedSizeBefore: Int,
) {
    val runway = olderPageLandingRunway(controller, listState, edgeItemKey, renderedSizeBefore) ?: return
    markPagingEvent(pageLandingEvent(runway))
}

/** The runway a landed page left, or null when nothing landed or the layout has no message row to measure from. */
private fun olderPageLandingRunway(
    controller: ConversationController,
    listState: LazyListState,
    edgeItemKey: String?,
    renderedSizeBefore: Int,
): Int? {
    val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
    val edgeTimelineIndex = rendered.indexOfFirst { it.id == edgeItemKey }
    if (edgeItemKey == null || rendered.size <= renderedSizeBefore || edgeTimelineIndex < 0) return null
    val edgeListIndex =
        conversationTimelineListIndex(
            timelineIndex = edgeTimelineIndex,
            timelineSize = rendered.size,
            trailingRowCount = controller.conversationTrailingRowCount(rendered.size),
        )
    val oldestVisibleIndex =
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull { conversationAnchorMessageId(it.key) != null }
            ?.index
    return oldestVisibleIndex?.let { olderPageRunwayRows(it, edgeListIndex) }
}

/** Prefix of an ordinary message row's list key. */
private const val MESSAGE_KEY_PREFIX = "msg:"

/** Length of a hex-encoded message id. */
private const val MESSAGE_ID_HEX_LENGTH = 64

/** Characters a hex-encoded message id may contain, in either case. */
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
