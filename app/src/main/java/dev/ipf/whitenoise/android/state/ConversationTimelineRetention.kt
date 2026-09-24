package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelinePageFfi

/**
 * Rows the app keeps beyond MDK's bounded window.
 *
 * MDK retains at most 200 rows and trims the far end as its window slides, so before this the app
 * dropped whatever the window dropped: three older pages evicted the newest rows, a jump back to
 * them needed a whole-list swap, and a new arrival while reading rebuilt the transcript. The app
 * now keeps every row it has shown as long as the window stays contiguous with them, up to
 * [MAX_RETAINED_TIMELINE_ROWS], evicts from whichever end is farthest from the rows the latest
 * window covers, and drops only the rows a window proves gone (see [departedRetainedIds]).
 */
internal const val MAX_RETAINED_TIMELINE_ROWS = 600

/**
 * Where a replaced window's ordinals start. An extended window aligns its rows to the ordinal of a
 * row both hold, which for an older page means counting down from it; starting a replacement this
 * high keeps that subtraction inside the unsigned range for any conversation a reader can page.
 */
internal const val AUTHORITATIVE_ORDER_BASE: ULong = 4_294_967_296uL // 2^32

/** Copies the mutable record, bridge and ordinal indexes before suspending on the preparation dispatcher. */
internal fun ConversationController.currentWindowApplySnapshot(): WindowApplySnapshot =
    currentWindowApplySnapshot(
        heldRecords = timelineRecords.values,
        pendingProjectionIds = pendingProjectionsAwaitingBridge.keys,
        heldOrder = authoritativeTimelineOrderByMessageId,
    )

/**
 * Installs the prepared ordinals: a replacement starts from nothing, an extension merges the page's
 * aligned ordinals over the ones retained rows already carry.
 */
internal fun ConversationController.installAuthoritativeOrder(prepared: PreparedWindowApply) {
    if (prepared.mode == WindowApplyMode.REPLACE) authoritativeTimelineOrderByMessageId.clear()
    authoritativeTimelineOrderByMessageId.putAll(prepared.authoritativeOrder)
}

/**
 * Keeps the retained timeline under [MAX_RETAINED_TIMELINE_ROWS] by dropping the rows farthest from
 * the window just applied, oldest-end and newest-end alike, so what the reader is looking at and
 * what MDK is following both survive. Rows without an ordinal, pending-bridge rows and optimistic
 * rows are never candidates.
 */
internal fun ConversationController.pruneRetainedTimelineRows(prepared: PreparedWindowApply) {
    val excess = timelineRecords.size - MAX_RETAINED_TIMELINE_ROWS
    if (excess <= 0 || prepared.authoritativeOrder.isEmpty()) return
    val windowLow = prepared.authoritativeOrder.values.min()
    val windowHigh = prepared.authoritativeOrder.values.max()
    val protected = prepared.authoritativeOrder.keys + pendingProjectionsAwaitingBridge.keys
    timelineRecords.keys
        .asSequence()
        .filter { it !in protected }
        .mapNotNull { id ->
            authoritativeTimelineOrderByMessageId[id]?.let { id to distanceOutside(it, windowLow, windowHigh) }
        }.filter { (_, distance) -> distance > 0uL }
        .sortedByDescending { (_, distance) -> distance }
        .take(excess)
        .forEach { (id, _) -> removeProjectedRecord(id) }
}

/**
 * Held rows an extending page proves gone. MDK's window is contiguous, so a held row ordered inside
 * the span of [pageOrder] that the page no longer carries was removed — deleted, or a disappearing
 * message that expired — and so was any held row beyond an edge the page marks final. Rows beyond
 * an edge with more history behind it were merely slid past and stay retained.
 */
internal fun departedRetainedIds(
    page: TimelinePageFfi,
    heldOrder: Map<String, ULong>,
    pageOrder: Map<String, ULong>,
): Set<String> {
    if (pageOrder.isEmpty()) return emptySet()
    val low = pageOrder.values.min()
    val high = pageOrder.values.max()
    val pageIds = page.messages.mapTo(HashSet(page.messages.size)) { it.messageIdHex }
    return heldOrder
        .filter { (id, ordinal) ->
            id !in pageIds &&
                when {
                    ordinal < low -> !page.hasMoreBefore
                    ordinal > high -> !page.hasMoreAfter
                    else -> true
                }
        }.keys
}

/** How far [order] lies outside the closed range, zero when inside it. */
private fun distanceOutside(
    order: ULong,
    low: ULong,
    high: ULong,
): ULong =
    when {
        order < low -> low - order
        order > high -> order - high
        else -> 0uL
    }
