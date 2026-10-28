package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListItem

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

/** Advisory explicit actions for the selected rows; commands still recheck the native owner and capabilities. */
internal data class ChatListSelectionActions(
    val showMarkRead: Boolean = false,
    val showMarkUnread: Boolean = false,
    val showMute: Boolean = false,
    val showUnmute: Boolean = false,
    val showPin: Boolean = false,
    val showUnpin: Boolean = false,
    val showMovePinned: Boolean = false,
    val archiveAction: ChatListBulkArchiveAction = ChatListBulkArchiveAction.Archive,
    val showAlternateArchive: Boolean = false,
)

/** Match the row menu while native action hints load; explicit native denials hide their direction. */
internal fun chatListSelectionActions(
    items: Collection<ChatListItem>,
    activeAccountIdHex: String?,
): ChatListSelectionActions =
    ChatListSelectionActions(
        showMarkRead = items.any { it.effectiveHasUnread(activeAccountIdHex) && it.actions?.canMarkRead != false },
        showMarkUnread = items.any { !it.effectiveHasUnread(activeAccountIdHex) && it.actions?.canMarkUnread != false },
        showMute = items.any { !it.engineMuted() && it.actions?.canMute != false },
        showUnmute = items.any { it.engineMuted() && it.actions?.canUnmute != false },
        showPin = items.any { !it.group.archived && !it.pinned() && it.actions?.canPin != false },
        showUnpin = items.any { it.pinned() && it.actions?.canUnpin != false },
        showMovePinned = items.isNotEmpty() && items.all { it.pinned() },
        archiveAction = chatListBulkArchiveAction(items.map { it.group.archived }),
        showAlternateArchive = items.any { it.group.archived } && items.any { !it.group.archived },
    )

/** Toggle one visible row without changing the other selected IDs. */
internal fun toggleChatListSelection(
    selected: Set<String>,
    chatId: String,
): Set<String> = if (chatId in selected) selected - chatId else selected + chatId

/** Start selection with the row that admitted the gesture. */
internal fun enterChatListSelection(chatId: String): Set<String> = setOf(chatId)

/** Capture the current visible IDs, independent of later filtering. */
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
    filterSheetOpen: Boolean = false,
    folderSelected: Boolean = false,
): Boolean = selectionMode || searchOpen || filterSheetOpen || folderSelected

internal enum class ChatListBackDismissal {
    ClearSelection,
    DismissFilterSheet,
    CloseSearch,
    ClearFolder,
}

/** Back dismisses selection first, then an open filter picker, then search. */
internal fun chatListBackDismissal(
    selectionMode: Boolean,
    searchState: GlobalSearchState,
    folderSelected: Boolean = false,
): ChatListBackDismissal? =
    when {
        selectionMode -> ChatListBackDismissal.ClearSelection
        searchState.filterSheetOpen -> ChatListBackDismissal.DismissFilterSheet
        searchState.isOpen -> ChatListBackDismissal.CloseSearch
        folderSelected -> ChatListBackDismissal.ClearFolder
        else -> null
    }

/** The chips row shows only while a filter is active; selection mode owns the header instead. */
internal fun shouldShowGlobalSearchFilterControls(
    searchState: GlobalSearchState,
    selectionMode: Boolean,
): Boolean = searchState.isOpen && !selectionMode && GlobalSearchActiveChips.from(searchState).count > 0

/** A category picker shows for the open category unless selection mode took over. */
internal fun shouldPresentGlobalSearchFilterSheet(
    searchState: GlobalSearchState,
    selectionMode: Boolean,
): Boolean = searchState.isOpen && searchState.filterSheetOpen && !selectionMode

/** Selection mode revokes an open filter picker; otherwise the state passes through. */
internal fun reconcileGlobalSearchFilterSheet(
    searchState: GlobalSearchState,
    selectionMode: Boolean,
): GlobalSearchState =
    if (searchState.filterSheetOpen && selectionMode) {
        GlobalSearchTransitions.dismissFilterSheet(searchState)
    } else {
        searchState
    }
