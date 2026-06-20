package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.ChatListRowFfi

/**
 * Aggregate unread messages for an account from durable chat-list rows.
 * Matches Marmot's `account_unread_total` projection: archived chats are
 * excluded. Used when the chat-list controller already holds the rows; prefer
 * [dev.ipf.marmotkit.Marmot.accountUnreadSummary] when refreshing every account
 * without loading each chat list.
 */
internal fun accountUnreadCount(rows: Iterable<ChatListRowFfi>): ULong =
    rows.fold(0uL) { total, row ->
        if (row.archived) total else total + row.unreadCount
    }
