package dev.ipf.whitenoise.android.ui.chats

internal enum class ChatListBulkArchiveAction {
    Archive,
    Unarchive,
}

/** Unarchive only when every selected chat is archived; mixed selection archives all. */
internal fun chatListBulkArchiveAction(archivedFlags: Collection<Boolean>): ChatListBulkArchiveAction =
    if (archivedFlags.isNotEmpty() && archivedFlags.all { it }) {
        ChatListBulkArchiveAction.Unarchive
    } else {
        ChatListBulkArchiveAction.Archive
    }

internal fun toggleChatListSelection(
    selected: Set<String>,
    chatId: String,
): Set<String> = if (chatId in selected) selected - chatId else selected + chatId

internal fun enterChatListSelection(chatId: String): Set<String> = setOf(chatId)

internal fun selectAllVisibleChats(visibleIds: Collection<String>): Set<String> = visibleIds.toSet()

/** Drop selections that fell off the current visible filtered list. */
internal fun reconcileChatListSelection(
    selected: Set<String>,
    visibleIds: Set<String>,
): Set<String> = selected.intersect(visibleIds)

/** Install BackHandler only while selection mode or search is active (#1169). */
internal fun chatListBackHandlerEnabled(
    selectionMode: Boolean,
    searchOpen: Boolean,
): Boolean = selectionMode || searchOpen
