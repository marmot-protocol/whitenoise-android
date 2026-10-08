package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.state.ChatFolderSort
import dev.ipf.whitenoise.android.state.ChatListItem

/** Presentation order over one complete native projection; recent mode preserves its draft-aware order. */
internal fun sortFolderChats(
    items: List<ChatListItem>,
    sort: ChatFolderSort,
    accountIdHex: String?,
    displayTitle: (ChatListItem) -> String,
): List<ChatListItem> {
    if (sort == ChatFolderSort.RECENT) return items
    val priority = items.filter { it.group.pendingConfirmation || it.pinned() }
    val ordinary = items.filterNot { it.group.pendingConfirmation || it.pinned() }
    val ordered =
        when (sort) {
            ChatFolderSort.NAME -> {
                val titles = ordinary.associate { it.id to localeInvariantFold(displayTitle(it)) }
                ordinary.sortedWith(compareBy<ChatListItem> { titles.getValue(it.id) }.thenBy { it.foldedId })
            }
            ChatFolderSort.UNREAD ->
                ordinary.filter { it.effectiveHasUnread(accountIdHex) } +
                    ordinary.filterNot { it.effectiveHasUnread(accountIdHex) }
            ChatFolderSort.RECENT -> ordinary
        }
    return priority + ordered
}
