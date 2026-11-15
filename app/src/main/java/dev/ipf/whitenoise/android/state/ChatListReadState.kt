package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi

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

/** Compares two optional read cursors only when both cursor pairs are complete. */
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

/** Returns the newest available timestamp without regressing a stored watermark. */
internal fun monotonicMaxTimelineAt(
    current: ULong?,
    incoming: ULong?,
): ULong? =
    when {
        incoming == null -> current
        current == null -> incoming
        else -> maxOf(current, incoming)
    }

/** Keeps the current complete watermark when a mark-read response omits one. */
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

/** Merges an accepted read cursor without allowing an older preview to replace a newer one. */
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

/** Whether the row's complete read cursor reaches its latest projected message. */
private fun readWatermarkCoversLastMessage(row: ChatListRowFfi): Boolean {
    val last = row.lastMessage
    val readAt = row.lastReadTimelineAt
    val readId = row.lastReadMessageIdHex
    return last != null &&
        readAt != null &&
        readId != null &&
        compareTimelineAtMessageIdHex(readAt, readId, last.timelineAt, last.messageIdHex) >= 0
}

/** Whether any unread field still claims unread state. */
private fun hasReadDerivedUnread(row: ChatListRowFfi): Boolean =
    when {
        row.unreadCount > 0uL -> true
        row.hasUnread -> true
        row.firstUnreadMessageIdHex != null -> true
        row.unreadMentionCount > 0uL -> true
        else -> row.unreadMention
    }

/** Clears contradictory unread fields when the authoritative watermark covers the last message. */
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

/**
 * Field-wise reducer for live chat-list subscription rows. Subscription rows
 * are ordered full projections, so all non-read fields are authoritative — in
 * particular, a deletion may move [ChatListRowFfi.lastMessage] backwards or to
 * null. Only the read-dependent fields can be stale when a synchronous
 * [markTimelineMessageRead] return races a previously queued subscription row.
 */
internal fun reduceSubscriptionChatListRow(
    current: ChatListRowFfi,
    incoming: ChatListRowFfi,
    trigger: ChatListUpdateTriggerFfi,
): ChatListRowFfi {
    val incomingReadComplete = incoming.lastReadTimelineAt != null && incoming.lastReadMessageIdHex != null
    val currentReadComplete = current.lastReadTimelineAt != null && current.lastReadMessageIdHex != null
    val incomingReadCompare =
        compareOptionalTimelineAtMessageIdHex(
            incoming.lastReadTimelineAt,
            incoming.lastReadMessageIdHex,
            current.lastReadTimelineAt,
            current.lastReadMessageIdHex,
        )
    val incomingReadTrusted = incomingReadComplete && (!currentReadComplete || incomingReadCompare!! >= 0)
    if (incomingReadTrusted) return reconcileReadDerivedUnread(incoming)

    val newLastMessage = incoming.lastMessage
    val currentLastMessage = current.lastMessage
    val distinctNewLastMessage = observesDistinctNewLastMessage(current, incoming, trigger)
    val advancesLastMessage =
        newLastMessage != null &&
            (
                currentLastMessage == null ||
                    newLastMessage.timelineAt > currentLastMessage.timelineAt ||
                    (distinctNewLastMessage && newLastMessage.timelineAt == currentLastMessage.timelineAt)
            )
    val advancesPastRead =
        newLastMessage != null &&
            (
                !currentReadComplete ||
                    newLastMessage.timelineAt > current.lastReadTimelineAt!! ||
                    (distinctNewLastMessage && newLastMessage.timelineAt == current.lastReadTimelineAt)
            )
    val addsUnread =
        trigger == ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE &&
            advancesLastMessage &&
            advancesPastRead &&
            incoming.unreadCount > current.unreadCount

    val unreadCount =
        if (addsUnread && current.unreadCount != ULong.MAX_VALUE) {
            current.unreadCount + 1uL
        } else {
            current.unreadCount
        }
    val unreadMentionCount =
        if (
            addsUnread &&
            incoming.unreadMentionCount > current.unreadMentionCount &&
            current.unreadMentionCount != ULong.MAX_VALUE
        ) {
            current.unreadMentionCount + 1uL
        } else {
            current.unreadMentionCount
        }
    return incoming.copy(
        lastReadTimelineAt = current.lastReadTimelineAt,
        lastReadMessageIdHex = current.lastReadMessageIdHex,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex =
            if (addsUnread && current.unreadCount == 0uL) {
                newLastMessage.messageIdHex
            } else {
                current.firstUnreadMessageIdHex
            },
        unreadMentionCount = unreadMentionCount,
        unreadMention = unreadMentionCount > 0uL,
    )
}

/**
 * Fold a successful [markTimelineMessageRead] row into the active chat list.
 * Does not gate on [ConversationController.lastReadMessageId]: concurrent
 * mark-read calls can complete out of order, and monotonic tuple merge rejects
 * rows that are already superseded.
 */
internal fun foldMarkReadReturnedRow(
    row: ChatListRowFfi,
    persistedLastReadTimelineAt: ULong?,
    applyChatListRow: (ChatListRowFfi) -> Unit,
): ULong? {
    applyChatListRow(row)
    return monotonicMaxTimelineAt(persistedLastReadTimelineAt, row.lastReadTimelineAt)
}
