package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatFolderSort
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.sortChatListItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Alternate presentation ordering preserves native priority and draft-aware recency. */
class ChatFolderSortingTest {
    @Test fun nameSortUsesDisplayedNamesAndStableIdsAcrossMoreThanOneRetainedWindow() {
        val rows = (0..250).map { item("id-$it") }
        val names = rows.associate { it.id to "Name %03d".format(250 - it.id.removePrefix("id-").toInt()) }
        val sorted = sortFolderChats(rows, ChatFolderSort.NAME, null) { names.getValue(it.id) }
        assertEquals("id-250", sorted.first().id)
        assertEquals(251, sorted.size)
        val tied = sortFolderChats(listOf(item("z"), item("a")), ChatFolderSort.NAME, null) { "SAME" }
        assertEquals(listOf("a", "z"), tied.map { it.id })
    }

    @Test fun unreadSortPreservesPendingPinsAndRecencyWithinEachPartition() {
        val invitation = item("invite", pending = true)
        val pin0 = item("pin0", pinned = true)
        val pin1 = item("pin1", pinned = true)
        val rows =
            listOf(
                invitation,
                pin0,
                pin1,
                item("read-draft"),
                item("unread-new", unread = true),
                item("read-old"),
                item("unread-old", unread = true),
            )
        assertEquals(
            listOf("invite", "pin0", "pin1", "unread-new", "unread-old", "read-draft", "read-old"),
            sortFolderChats(rows, ChatFolderSort.UNREAD, null) { it.id }.map { it.id },
        )
        val named = sortFolderChats(rows, ChatFolderSort.NAME, null) { it.id }
        assertEquals(listOf("invite", "pin0", "pin1"), named.take(3).map { it.id })
    }

    @Test fun recentReturnsTheExistingDraftAwareOrderAndUnreadKeepsItWithinPartitions() {
        val rows = sortChatListItems(listOf(item("message"), item("draft"))) { if (it.id == "draft") 999uL else null }
        assertSame(rows, sortFolderChats(rows, ChatFolderSort.RECENT, null) { it.id })
        assertEquals(rows, sortFolderChats(rows, ChatFolderSort.UNREAD, null) { it.id })
    }

    private fun item(
        id: String,
        unread: Boolean = false,
        pinned: Boolean = false,
        pending: Boolean = false,
    ): ChatListItem {
        val base = ChatRowPortFixtures.item()
        return base.copy(
            group = base.group.copy(groupIdHex = id, pendingConfirmation = pending),
            projection =
                base.projection!!.copy(
                    groupIdHex = id,
                    hasUnread = unread,
                    unreadCount = if (unread) 1uL else 0uL,
                    pinned = pinned,
                    pendingConfirmation = pending,
                ),
        )
    }
}
