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
    }
}
