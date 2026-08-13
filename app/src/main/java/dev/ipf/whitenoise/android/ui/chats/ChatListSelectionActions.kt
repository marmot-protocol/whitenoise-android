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

internal fun shouldShowGlobalSearchFilterControls(
    searchState: GlobalSearchState,
    interactiveSectionsAvailable: Boolean,
    selectionMode: Boolean,
): Boolean =
    searchState.isOpen &&
        !selectionMode &&
        (interactiveSectionsAvailable || GlobalSearchActiveChips.from(searchState).count > 0)

internal fun shouldPresentGlobalSearchFilterSheet(
    searchState: GlobalSearchState,
    interactiveSectionsAvailable: Boolean,
    selectionMode: Boolean,
): Boolean = searchState.filterSheetOpen && interactiveSectionsAvailable && !selectionMode

internal fun reconcileGlobalSearchFilterSheet(
    searchState: GlobalSearchState,
    interactiveSectionsAvailable: Boolean,
    selectionMode: Boolean,
): GlobalSearchState =
    if (searchState.filterSheetOpen && (!interactiveSectionsAvailable || selectionMode)) {
        GlobalSearchTransitions.dismissFilterSheet(searchState)
    } else {
        searchState
    }
