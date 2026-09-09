package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi

/** Restores a previous preview only while the optimistic message still owns it. */
internal fun rollbackOptimisticChatListPreview(
    current: ChatListRowFfi,
    previous: ChatListRowFfi,
    optimisticMessageIdHex: String,
): ChatListRowFfi =
    if (current.lastMessage?.messageIdHex == optimisticMessageIdHex) {
        previous
    } else {
        current
    }

/** Orders engine timeline cursors by timestamp and then stable message id. */
internal fun compareTimelineAtMessageIdHex(
    leftAt: ULong,
    leftId: String,
    rightAt: ULong,
    rightId: String,
): Int {
    val atCompare = leftAt.compareTo(rightAt)
    if (atCompare != 0) return atCompare
    return leftId.compareTo(rightId)
}

@Suppress("ReturnCount") // The two incomplete cursor halves are independent invalid states.
internal fun compareOptionalTimelineAtMessageIdHex(
    leftAt: ULong?,
    leftId: String?,
    rightAt: ULong?,
    rightId: String?,
): Int? {
    if (leftAt == null || rightAt == null) return null
    if (leftId == null || rightId == null) return null
    return compareTimelineAtMessageIdHex(leftAt, leftId, rightAt, rightId)
}

internal fun monotonicMaxTimelineAt(
    current: ULong?,
    incoming: ULong?,
): ULong? =
    when {
        incoming == null -> current
        current == null -> incoming
        else -> maxOf(current, incoming)
    }

private fun mergeMarkReadReadWatermark(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
): Pair<ULong?, String?> {
    val incomingAt = incoming.lastReadTimelineAt
    val incomingId = incoming.lastReadMessageIdHex
    if (incomingAt == null || incomingId == null) {
        return current.lastReadTimelineAt to current.lastReadMessageIdHex
    }
    return incomingAt to incomingId
}

/**
 * Reconciles a mark-read return row with the in-memory row, rejecting a
 * projection whose read cursor is strictly older than the current cursor.
 */
internal fun mergeMarkReadChatListRow(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
): ChatListRowFfi? {
    val readCompare =
        compareOptionalTimelineAtMessageIdHex(
            incoming.lastReadTimelineAt,
            incoming.lastReadMessageIdHex,
            current.lastReadTimelineAt,
            current.lastReadMessageIdHex,
        )
    if (readCompare != null && readCompare < 0) return null

    return mergeMarkReadWithAcceptedCursor(current, incoming)
}

private fun mergeMarkReadWithAcceptedCursor(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
): ChatListRowFfi {
    val incomingLast = incoming.lastMessage
    val currentLast = current.lastMessage
    if (currentLast != null && incomingLast != null) {
        val lastCompare =
            compareTimelineAtMessageIdHex(
                incomingLast.timelineAt,
                incomingLast.messageIdHex,
                currentLast.timelineAt,
                currentLast.messageIdHex,
            )
        if (lastCompare < 0) {
            val (readTimelineAt, readMessageIdHex) = mergeMarkReadReadWatermark(current, incoming)
            return reconcileReadDerivedUnread(
                current.copy(
                    lastReadMessageIdHex = readMessageIdHex,
                    lastReadTimelineAt = readTimelineAt,
                ),
            )
        }
    }
    val (readTimelineAt, readMessageIdHex) = mergeMarkReadReadWatermark(current, incoming)
    return reconcileReadDerivedUnread(
        incoming.copy(
            lastMessage = incoming.lastMessage ?: current.lastMessage,
            lastReadMessageIdHex = readMessageIdHex,
            lastReadTimelineAt = readTimelineAt,
        ),
    )
}

private fun readWatermarkCoversLastMessage(row: ChatListRowFfi): Boolean {
    val last = row.lastMessage
    val readAt = row.lastReadTimelineAt
    val readId = row.lastReadMessageIdHex
    return last != null &&
        readAt != null &&
        readId != null &&
        compareTimelineAtMessageIdHex(readAt, readId, last.timelineAt, last.messageIdHex) >= 0
}

private fun hasReadDerivedUnread(row: ChatListRowFfi): Boolean =
    when {
        row.unreadCount > 0uL -> true
        row.hasUnread -> true
        row.firstUnreadMessageIdHex != null -> true
        row.unreadMentionCount > 0uL -> true
        else -> row.unreadMention
    }

internal fun reconcileReadDerivedUnread(incoming: ChatListRowFfi): ChatListRowFfi =
    if (readWatermarkCoversLastMessage(incoming) && hasReadDerivedUnread(incoming)) {
        incoming.copy(
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            unreadMentionCount = 0uL,
            unreadMention = false,
        )
    } else {
        incoming
    }
