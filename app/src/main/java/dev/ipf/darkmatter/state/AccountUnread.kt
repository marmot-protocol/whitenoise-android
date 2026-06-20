package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.ChatListRowFfi

/**
 * Aggregate the durable chat-list projection for an account into the account
 * switcher's unread badge count. The projection comes from Marmot's SQLite
 * source of truth; Android keeps only this short-lived UI snapshot.
 */
internal fun accountUnreadCount(rows: Iterable<ChatListRowFfi>): ULong = rows.fold(0uL) { total, row -> total + row.unreadCount }
