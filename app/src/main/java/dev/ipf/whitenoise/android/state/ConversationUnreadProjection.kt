package dev.ipf.whitenoise.android.state

import android.util.Log

/**
 * Index of the first (oldest) still-unread received message in [timeline]
 * given the chat-list projection's [unreadCount]. Returns -1 when nothing is
 * unread, the timeline is empty, or the loaded window holds fewer than
 * [unreadCount] received messages (caller falls back to the bottom).
 */
internal fun firstUnreadReceivedIndex(
    timeline: List<TimelineMessage>,
    unreadCount: Int,
): Int {
    if (unreadCount <= 0 || timeline.isEmpty()) return -1
    var seen = 0
    for (index in timeline.indices.reversed()) {
        val record = timeline[index].record
        // Derived-state rows (kind 1009 edits, 1210 group system events)
        // arrive as `received` but never count as new chat — skip them so
        // an avatar change or in-place edit doesn't inflate the unread
        // badge or shift the "first unread" anchor away from real
        // messages.
        if (record.direction == "received" && !isDerivedStateKind(record.kind)) {
            seen += 1
            if (seen == unreadCount) return index
        }
    }
    // The loaded window doesn't contain enough received messages to satisfy
    // the unread count; signal "use the bottom" rather than the top, since
    // the read state still advances as the user scrolls.
    return -1
}

/**
 * Count of received messages positioned after the read anchor in [timeline].
 * A null anchor is treated as "nothing read yet", so the count starts from
 * the first row; an anchor the window does not hold counts the same way, so
 * callers that may be looking at evicted history decide for themselves
 * (see `ConversationUnreadBadge`). Anchoring on a message id (not an index)
 * keeps the count stable when load-older prepends shift every index by the
 * same offset.
 */
internal fun countUnreadIncoming(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): Int {
    if (timeline.isEmpty()) return 0
    val anchorIdx =
        readAnchorMessageId?.let { id ->
            timeline.indexOfFirst { it.record.messageIdHex == id }
        } ?: -1
    return timeline.drop(anchorIdx + 1).count {
        it.record.direction == "received" && !isDerivedStateKind(it.record.kind)
    }
}

/**
 * Reconcile the chat-list projection against the loaded timeline and its
 * durable read watermark. A watermark present in the loaded window is stronger
 * evidence than a stale projection; without one, preserve counts larger than
 * the loaded window so pagination can still reveal the real boundary.
 */
internal fun reconciledConversationEntryUnreadCount(
    projectionUnread: Int,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): Int {
    val projected = projectionUnread.coerceAtLeast(0)
    return if (projected == 0 || timeline.isEmpty()) {
        projected
    } else {
        val loadedReceived =
            timeline.count { message ->
                message.record.direction == "received" && !isDerivedStateKind(message.record.kind)
            }
        val anchorLoaded =
            !readAnchorMessageId.isNullOrBlank() &&
                timeline.any { it.record.messageIdHex == readAnchorMessageId }
        if (!anchorLoaded && projected > loadedReceived) {
            projected
        } else {
            minOf(projected, countUnreadIncoming(timeline, readAnchorMessageId))
        }
    }
}

/** Privacy-safe snapshot when entry projection unread would mis-anchor the timeline. */
internal data class UnreadCountDivergenceReport(
    val projectionUnread: Int,
    val timelineUnread: Int,
    val loadedReceivedCount: Int,
)

/**
 * Detect an inflated entry unread count that fits the loaded received window and
 * would drive [firstUnreadReceivedIndex] toward the top. Returns null when the
 * counts agree, the timeline is empty, projection unread is not above timeline
 * unread, or projection unread exceeds the loaded window (falls back to bottom).
 */
internal fun unreadCountDivergenceReport(
    projectionUnread: Int,
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
): UnreadCountDivergenceReport? {
    if (timeline.isEmpty()) return null
    val timelineUnread = countUnreadIncoming(timeline, readAnchorMessageId)
    if (projectionUnread <= timelineUnread) return null
    val loadedReceived =
        timeline.count { row ->
            row.record.direction == "received" && !isDerivedStateKind(row.record.kind)
        }
    if (projectionUnread > loadedReceived) return null
    return UnreadCountDivergenceReport(
        projectionUnread = projectionUnread,
        timelineUnread = timelineUnread,
        loadedReceivedCount = loadedReceived,
    )
}

internal fun logUnreadCountDivergence(
    tag: String,
    report: UnreadCountDivergenceReport,
) {
    Log.w(
        tag,
        "unread divergence projection=${report.projectionUnread} timeline=${report.timelineUnread} " +
            "loadedReceived=${report.loadedReceivedCount} source=inflated_entry_projection",
    )
}

// Derived-state event kinds: rows that arrive as `received` from the
// network but represent state changes (edits, group system events), not
// new chat. They never inflate unread counts and never block read-anchor
// advancement.
internal fun isDerivedStateKind(kind: ULong): Boolean = kind == 1009uL || kind == 1210uL

/**
 * Message ids of unread received mentions in [timeline], oldest first — drives
 * the in-conversation jump-to-mention chip. A null [readAnchorMessageId] counts
 * from the first loaded row, but a non-null anchor that has fallen out of the
 * loaded window returns no ids. For this chip, hiding is safer than resurrecting
 * an already-read mention after a conversation is recreated. Only kind-9 chat
 * rows can be mentions; [mentionsActiveAccount] is passed in so this stays pure
 * and the ui-layer NIP-27 detection isn't pulled into the state layer.
 */
internal fun unreadReceivedMentionIds(
    timeline: List<TimelineMessage>,
    readAnchorMessageId: String?,
    mentionsActiveAccount: (TimelineMessage) -> Boolean,
): List<String> {
    if (timeline.isEmpty()) return emptyList()
    val anchorIdx =
        readAnchorMessageId?.let { id ->
            timeline.indexOfFirst { it.record.messageIdHex == id }
        } ?: -1
    if (readAnchorMessageId != null && anchorIdx < 0) return emptyList()
    return timeline
        .drop(anchorIdx + 1)
        .filter { it.record.direction == "received" && it.record.kind == 9uL && mentionsActiveAccount(it) }
        .mapNotNull { it.record.messageIdHex.takeIf { id -> id.isNotBlank() } }
}
