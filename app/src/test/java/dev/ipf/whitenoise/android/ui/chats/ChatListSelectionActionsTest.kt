package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSelectionActionsTest {
    @Test
    fun bulkArchiveActionUnarchivesOnlyWhenEverySelectedChatIsArchived() {
        assertEquals(ChatListBulkArchiveAction.Unarchive, chatListBulkArchiveAction(listOf(true)))
        assertEquals(ChatListBulkArchiveAction.Unarchive, chatListBulkArchiveAction(listOf(true, true)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(listOf(false)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(listOf(true, false)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(emptyList()))
    }

    @Test
    fun selectionHelpersToggleEnterAndSelectAll() {
        assertEquals(setOf("a"), enterChatListSelection("a"))
        assertEquals(setOf("a", "b"), toggleChatListSelection(setOf("a"), "b"))
        assertEquals(setOf("a"), toggleChatListSelection(setOf("a", "b"), "b"))
        assertEquals(setOf("a", "b", "c"), selectAllVisibleChats(listOf("a", "b", "c")))
    }

    @Test
    fun reconcileSelectionKeepsOnlyVisibleIds() {
        assertEquals(
            setOf("b"),
            reconcileChatListSelection(setOf("a", "b", "c"), setOf("b", "d")),
        )
    }

    @Test
    fun backHandlerEnabledOnlyDuringSelectionOrSearch() {
        assertFalse(chatListBackHandlerEnabled(selectionMode = false, searchOpen = false))
        assertTrue(chatListBackHandlerEnabled(selectionMode = true, searchOpen = false))
        assertTrue(chatListBackHandlerEnabled(selectionMode = false, searchOpen = true))
        assertTrue(chatListBackHandlerEnabled(selectionMode = true, searchOpen = true))
        assertTrue(chatListBackHandlerEnabled(selectionMode = false, searchOpen = false, filterSheetOpen = true))
    }

    /** Back dismissal prioritizes selection then filter sheet then search. */
    @Test
    fun backDismissalPrioritizesSelectionThenFilterSheetThenSearch() {
        val searchOpen = GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Date)
        assertEquals(ChatListBackDismissal.ClearSelection, chatListBackDismissal(selectionMode = true, searchOpen))
        assertEquals(
            ChatListBackDismissal.DismissFilterSheet,
            chatListBackDismissal(selectionMode = false, searchOpen),
        )
        assertEquals(
            ChatListBackDismissal.CloseSearch,
            chatListBackDismissal(selectionMode = false, GlobalSearchState(isOpen = true)),
        )
        assertEquals(null, chatListBackDismissal(selectionMode = false, GlobalSearchState()))
    }

    /** Filter picker cannot cover selection and needs an open category. */
    @Test
    fun filterPickerCannotCoverSelectionAndNeedsAnOpenCategory() {
        val requested = GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Date)

        assertFalse(shouldPresentGlobalSearchFilterSheet(searchState = requested, selectionMode = true))
        assertTrue(shouldPresentGlobalSearchFilterSheet(searchState = requested, selectionMode = false))
        assertFalse(
            shouldPresentGlobalSearchFilterSheet(searchState = GlobalSearchState(isOpen = true), selectionMode = false),
        )
        assertFalse(
            shouldPresentGlobalSearchFilterSheet(
                searchState = requested.copy(isOpen = false),
                selectionMode = false,
            ),
        )
    }

    /** Filter chips show only with active filters outside selection. */
    @Test
    fun filterChipsShowOnlyWithActiveFiltersOutsideSelection() {
        val emptySearch = GlobalSearchState(isOpen = true)
        val filteredSearch =
            emptySearch.copy(chatFilters = setOf(GlobalSearchChatFilter("chat-id", "Alice")))

        assertFalse(shouldShowGlobalSearchFilterControls(searchState = emptySearch, selectionMode = false))
        assertTrue(shouldShowGlobalSearchFilterControls(searchState = filteredSearch, selectionMode = false))
        assertFalse(shouldShowGlobalSearchFilterControls(searchState = filteredSearch, selectionMode = true))
        assertFalse(
            shouldShowGlobalSearchFilterControls(
                searchState = filteredSearch.copy(isOpen = false),
                selectionMode = false,
            ),
        )
    }

    /** Selection revokes an open filter picker before back dispatch. */
    @Test
    fun selectionRevokesAnOpenFilterPickerBeforeBackDispatch() {
        val requested = GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Date)

        val reconciled = reconcileGlobalSearchFilterSheet(searchState = requested, selectionMode = true)
        assertFalse(reconciled.filterSheetOpen)
        assertEquals(ChatListBackDismissal.ClearSelection, chatListBackDismissal(selectionMode = true, reconciled))

        val untouched = reconcileGlobalSearchFilterSheet(searchState = requested, selectionMode = false)
        assertEquals(requested, untouched)
        assertEquals(ChatListBackDismissal.DismissFilterSheet, chatListBackDismissal(selectionMode = false, untouched))
    }
}
