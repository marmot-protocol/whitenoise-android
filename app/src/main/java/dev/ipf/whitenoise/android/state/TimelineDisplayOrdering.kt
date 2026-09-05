package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi

/**
 * Total fallback order for rows that do not have an MDK authoritative ordinal:
 * engine timestamp, then local arrival order, then message id.
 *
 * Written as an explicit chain rather than `compareValuesBy`. That helper
 * takes its selectors as `vararg (T) -> Comparable<*>?`, so each call
 * allocates a `Function1[3]` and boxes both `ULong` keys through
 * `ULong.box-impl` to satisfy the `Comparable` return type. This runs once
 * per comparison of an O(n log n) sort that re-runs on every timeline
 * publish. `ULong.compareTo` compares the underlying longs directly.
 */
internal fun compareTimelineMessages(
    left: TimelineMessage,
    right: TimelineMessage,
): Int {
    val byRecordedAt = left.record.recordedAt.compareTo(right.record.recordedAt)
    if (byRecordedAt != 0) return byRecordedAt
    val byTimelineOrder = left.timelineOrder.compareTo(right.timelineOrder)
    return if (byTimelineOrder != 0) byTimelineOrder else left.id.compareTo(right.id)
}

/**
 * Preserves MDK's relative order for authoritative rows while merging local
 * overlays at their timestamp position. Local rows include optimistic sends,
 * unresolved local projections, and transient position bridges.
 */
internal fun orderTimelineMessagesForDisplay(messages: List<TimelineMessage>): List<TimelineMessage> {
    val distinct = messages.distinctBy { it.id }
    val authoritative =
        distinct
            .filter { it.authoritativeOrder != null }
            .sortedWith(compareBy<TimelineMessage> { it.authoritativeOrder }.thenBy { it.id })
            .toMutableList()
    val overlays = distinct.filter { it.authoritativeOrder == null }
    val messageIds = distinct.mapTo(mutableSetOf()) { it.displayMessageIdHex() }
    val anchoredOverlays =
        overlays.filter { overlay ->
            overlay.displayAfterMessageIdHex?.let(messageIds::contains) == true
        }
    val anchoredIds = anchoredOverlays.mapTo(mutableSetOf(), TimelineMessage::id)
    overlays
        .filterNot { it.id in anchoredIds }
        .sortedWith(::compareTimelineMessages)
        .forEach { overlay -> insertTimelineOverlayByFallbackOrder(authoritative, overlay) }

    val childrenByParent =
        anchoredOverlays
            .groupBy(TimelineMessage::displayAfterMessageIdHex)
            .mapValues { (_, children) -> children.sortedWith(::compareTimelineMessages) }
    val ordered = ArrayList<TimelineMessage>(distinct.size)
    val emittedIds = mutableSetOf<String>()

    /** Emits a row and its durable descendants as one contiguous display chain. */
    fun appendWithAnchoredChildren(row: TimelineMessage) {
        if (!emittedIds.add(row.id)) return
        ordered += row
        childrenByParent[row.displayMessageIdHex()].orEmpty().forEach(::appendWithAnchoredChildren)
    }

    authoritative.forEach(::appendWithAnchoredChildren)
    // Malformed or cyclic durable links cannot be allowed to hide rows. Keep
    // their deterministic fallback order if no rendered parent reached them.
    anchoredOverlays
        .filterNot { it.id in emittedIds }
        .sortedWith(::compareTimelineMessages)
        .forEach { overlay -> insertTimelineOverlayByFallbackOrder(ordered, overlay) }
    return ordered
}

/** Whether a projection should retain its MDK page position in the display order. */
internal fun TimelineMessageRecordFfi.usesAuthoritativePageOrder(): Boolean =
    invalidationStatus == null ||
        sourceEpoch != null ||
        sourceMessageIdHex != null

/** Source-level message identity used by durable stream parent links. */
private fun TimelineMessage.displayMessageIdHex(): String = projected?.messageIdHex ?: record.messageIdHex

/** Inserts an unanchored overlay without disturbing authoritative relative order. */
private fun insertTimelineOverlayByFallbackOrder(
    rows: MutableList<TimelineMessage>,
    overlay: TimelineMessage,
) {
    if (overlay.projected?.usesAuthoritativePageOrder() != false) {
        val precedingIndex = rows.indexOfLast { row -> compareTimelineMessages(row, overlay) <= 0 }
        rows.add(precedingIndex + 1, overlay)
        return
    }
    // A terminal row can fall inside an authoritative timestamp inversion.
    // Once the authoritative prefix reaches the same or a later timestamp, a
    // lower timestamp cannot reopen a safe gap or the stale row could become
    // the live head again. Previously inserted overlays remain stable.
    var authoritativePrefixMaximumAt: ULong? = null
    var insertionIndex = 0
    for ((index, row) in rows.withIndex()) {
        if (row.authoritativeOrder != null) {
            authoritativePrefixMaximumAt =
                maxOf(authoritativePrefixMaximumAt ?: row.record.recordedAt, row.record.recordedAt)
            if (authoritativePrefixMaximumAt >= overlay.record.recordedAt) break
        }
        insertionIndex = index + 1
    }
    rows.add(insertionIndex, overlay)
}
