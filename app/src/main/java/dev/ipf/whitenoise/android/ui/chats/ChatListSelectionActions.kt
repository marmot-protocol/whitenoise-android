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
    filterSheetOpen: Boolean = false,
): Boolean = selectionMode || searchOpen || filterSheetOpen

internal enum class ChatListBackDismissal {
    ClearSelection,
    DismissFilterSheet,
    CloseSearch,
}

/** Back dismisses selection first, then an open filter picker, then search. */
internal fun chatListBackDismissal(
    selectionMode: Boolean,
    searchState: GlobalSearchState,
): ChatListBackDismissal? =
    when {
        selectionMode -> ChatListBackDismissal.ClearSelection
        searchState.filterSheetOpen -> ChatListBackDismissal.DismissFilterSheet
        searchState.isOpen -> ChatListBackDismissal.CloseSearch
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
