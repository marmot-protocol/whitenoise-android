package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.noChatRowActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSelectionActionsTest {
    /** An empty capture offers no row state transition or pin-order move. */
    @Test fun emptySelectionOffersNoRowActions() {
        assertEquals(ChatListSelectionActions(), chatListSelectionActions(emptyList(), null))
    }

    /** Uniform read state offers its explicit inverse; mixed state offers both destinations. */
    @Test fun readAndUnreadSelectionsOfferOnlyTheirApplicableDirections() {
        val read = selectionItem(chatRow("read"))
        val unread = selectionItem(chatRow("unread").copy(manuallyMarkedUnread = true))
        val allRead = chatListSelectionActions(listOf(read, read), null)
        assertFalse(allRead.showMarkRead)
        assertTrue(allRead.showMarkUnread)
        val allUnread = chatListSelectionActions(listOf(unread, unread), null)
        assertTrue(allUnread.showMarkRead)
        assertFalse(allUnread.showMarkUnread)
        val mixed = chatListSelectionActions(listOf(read, unread), null)
        assertTrue(mixed.showMarkRead)
        assertTrue(mixed.showMarkUnread)
    }

    /** Mixed native state offers separate read, mute, pin and archive directions. */
    @Test fun mixedSelectionsOfferEachApplicableExplicitState() {
        val unread = selectionItem(chatRow("unread").copy(manuallyMarkedUnread = true))
        val opposite = selectionItem(chatRow("opposite").copy(muted = true, pinned = true, archived = true))
        val actual = chatListSelectionActions(listOf(unread, opposite), null)
        assertEquals(
            ChatListSelectionActions(
                showMarkRead = true,
                showMarkUnread = true,
                showMute = true,
                showUnmute = true,
                showPin = true,
                showUnpin = true,
                showAlternateArchive = true,
            ),
            actual,
        )
    }

    /** Uniform mute and pin state offers only their inverses and retains manual ordering. */
    @Test fun mutedPinnedSelectionOffersInverseActionsAndMoves() {
        val item = selectionItem(chatRow("muted-pinned").copy(muted = true, pinned = true))
        val actual = chatListSelectionActions(listOf(item, item), null)
        assertFalse(actual.showMute)
        assertTrue(actual.showUnmute)
        assertFalse(actual.showPin)
        assertTrue(actual.showUnpin)
        assertTrue(actual.showMovePinned)
    }

    /** Mute and pin state remain independent when their native values differ. */
    @Test fun muteAndPinDirectionsCannotBeCoupledByTheSelection() {
        val muted = selectionItem(chatRow("muted").copy(muted = true, pinned = false))
        val mutedActions = chatListSelectionActions(listOf(muted), null)
        assertFalse(mutedActions.showMute)
        assertTrue(mutedActions.showUnmute)
        assertTrue(mutedActions.showPin)
        assertFalse(mutedActions.showUnpin)
        val pinned = selectionItem(chatRow("pinned").copy(muted = false, pinned = true))
        val pinnedActions = chatListSelectionActions(listOf(pinned), null)
        assertTrue(pinnedActions.showMute)
        assertFalse(pinnedActions.showUnmute)
        assertFalse(pinnedActions.showPin)
        assertTrue(pinnedActions.showUnpin)
    }

    /** An entirely archived capture restores rows and never offers a new pin. */
    @Test fun archivedSelectionOffersRestoreButNeverPin() {
        val archived = selectionItem(chatRow("archived").copy(archived = true))
        val actual = chatListSelectionActions(listOf(archived), null)
        assertEquals(ChatListBulkArchiveAction.Unarchive, actual.archiveAction)
        assertFalse(actual.showPin)
        assertFalse(actual.showAlternateArchive)
    }

    /** Missing advisory hints match the row menu; an explicit denial still hides its direction. */
    @Test fun unloadedCapabilitiesMatchRowMenuButExplicitDenialsStayHidden() {
        val row = chatRow("loading").copy(manuallyMarkedUnread = true)
        val loading = chatListSelectionActions(listOf(selectionItem(row, null)), null)
        assertTrue(loading.showMarkRead)
        assertTrue(loading.showMute)
        assertTrue(loading.showPin)
        val denied = chatListSelectionActions(listOf(selectionItem(row, noChatRowActions())), null)
        assertFalse(denied.showMarkRead)
        assertFalse(denied.showMute)
        assertFalse(denied.showPin)
        val inverseRow = row.copy(manuallyMarkedUnread = false, muted = true, pinned = true)
        val inverse = chatListSelectionActions(listOf(selectionItem(inverseRow, null)), null)
        assertTrue(inverse.showMarkUnread)
        assertTrue(inverse.showUnmute)
        assertTrue(inverse.showUnpin)
    }

    /** Project ordinary native test rows without replacing any selection-state logic. */
    private fun selectionItem(
        row: ChatListRowFfi,
        actions: ChatListRowActionsFfi? =
            noChatRowActions().copy(
                canMarkRead = true,
                canMarkUnread = true,
                canMute = true,
                canUnmute = true,
                canPin = true,
                canUnpin = true,
            ),
    ): ChatListItem = chatListItemFromProjection(row, actions = actions)

    /** Committed Back unwinds search/selection before a folder; root has no callback and can exit. */
    @Test fun folderBackIsTheLastPresentationLayerBeforeRootExit() {
        assertTrue(chatListBackHandlerEnabled(false, false, folderSelected = true))
        assertEquals(ChatListBackDismissal.ClearFolder, chatListBackDismissal(false, GlobalSearchState(), true))
        assertEquals(
            ChatListBackDismissal.CloseSearch,
            chatListBackDismissal(false, GlobalSearchState(isOpen = true), true),
        )
        assertEquals(ChatListBackDismissal.ClearSelection, chatListBackDismissal(true, GlobalSearchState(), true))
        assertEquals(null, chatListBackDismissal(false, GlobalSearchState(), false))
    }

    /** Mixed archive state targets Archive; only an entirely archived capture targets Restore. */
    @Test
    fun bulkArchiveActionUnarchivesOnlyWhenEverySelectedChatIsArchived() {
        assertEquals(ChatListBulkArchiveAction.Unarchive, chatListBulkArchiveAction(listOf(true)))
        assertEquals(ChatListBulkArchiveAction.Unarchive, chatListBulkArchiveAction(listOf(true, true)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(listOf(false)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(listOf(true, false)))
        assertEquals(ChatListBulkArchiveAction.Archive, chatListBulkArchiveAction(emptyList()))
    }

    /** Selection ID helpers retain the untouched selected rows. */
    @Test
    fun selectionHelpersToggleEnterAndSelectAll() {
        assertEquals(setOf("a"), enterChatListSelection("a"))
        assertEquals(setOf("a", "b"), toggleChatListSelection(setOf("a"), "b"))
        assertEquals(setOf("a"), toggleChatListSelection(setOf("a", "b"), "b"))
        assertEquals(setOf("a", "b", "c"), selectAllVisibleChats(listOf("a", "b", "c")))
    }

    /** Filtering drops only IDs that leave the current visible selection. */
    @Test
    fun reconcileSelectionKeepsOnlyVisibleIds() {
        assertEquals(
            setOf("b"),
            reconcileChatListSelection(setOf("a", "b", "c"), setOf("b", "d")),
        )
    }

    /** Back is intercepted only while a dismissible chat-list layer is active. */
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
