package dev.ipf.whitenoise.android.state

import java.util.Locale

/** Device-local ordering of the chats inside one folder; stored enum names are stable preference values. */
enum class ChatFolderSortOrder {
    RECENT,
    NAME,
    UNREAD,
    ;

    companion object {
        /** Older folders and unknown future values retain the existing recent-activity experience. */
        fun fromStored(value: String): ChatFolderSortOrder = entries.firstOrNull { it.name == value } ?: RECENT
    }
}

/**
 * Reorders an already filtered, draft-aware source without changing pending or manually pinned precedence.
 * Stable unread partitioning retains source recency; displayed name ties use the immutable chat identity.
 */
internal fun sortFolderChatItems(
    items: List<ChatListItem>,
    order: ChatFolderSortOrder,
    activeAccountIdHex: String?,
    displayTitle: (ChatListItem) -> String,
): List<ChatListItem> {
    if (order == ChatFolderSortOrder.RECENT) return items
    val (fixed, ordinary) = items.partition { it.group.pendingConfirmation || it.pinned() }
    val sorted =
        when (order) {
            ChatFolderSortOrder.NAME -> {
                val titles = ordinary.associate { it.id to displayTitle(it).lowercase(Locale.ROOT) }
                ordinary.sortedWith(compareBy<ChatListItem> { titles.getValue(it.id) }.thenBy { it.id })
            }
            ChatFolderSortOrder.UNREAD -> ordinary.sortedByDescending { it.effectiveHasUnread(activeAccountIdHex) }
            ChatFolderSortOrder.RECENT -> ordinary
        }
    return fixed + sorted
}
