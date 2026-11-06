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

    /** Verifies Unread and Name keep the pinned prefix, then invites, over the native base order. */
    @Test fun unreadSortPreservesPinsPendingAndRecencyWithinEachPartition() {
        // The folder sort receives the base chat-list order, so build the
        // fixture through it — an invite arriving must not reach the top here.
        val rows =
            sortChatListItems(
                listOf(
                    item("invite", pending = true),
                    item("read-draft"),
                    item("pin1", pinned = true, pinnedPosition = 1u),
                    item("unread-new", unread = true),
                    item("pin0", pinned = true, pinnedPosition = 0u),
                    item("read-old"),
                    item("unread-old", unread = true),
                ),
            )
        assertEquals(
            listOf("pin0", "pin1", "invite", "unread-new", "unread-old", "read-draft", "read-old"),
            sortFolderChats(rows, ChatFolderSort.UNREAD, null) { it.id }.map { it.id },
        )
        val named = sortFolderChats(rows, ChatFolderSort.NAME, null) { it.id }
        assertEquals(listOf("pin0", "pin1", "invite"), named.take(3).map { it.id })
        val recent = sortFolderChats(rows, ChatFolderSort.RECENT, null) { it.id }
        assertEquals(listOf("pin0", "pin1", "invite"), recent.take(3).map { it.id })
    }

    @Test fun recentReturnsTheExistingDraftAwareOrderAndUnreadKeepsItWithinPartitions() {
        val rows = sortChatListItems(listOf(item("message"), item("draft"))) { if (it.id == "draft") 999uL else null }
        assertSame(rows, sortFolderChats(rows, ChatFolderSort.RECENT, null) { it.id })
        assertEquals(rows, sortFolderChats(rows, ChatFolderSort.UNREAD, null) { it.id })
    }

    /** A chat row fixture with the unread, pinned, pending and manual pin position states the sort reads. */
    private fun item(
        id: String,
        unread: Boolean = false,
        pinned: Boolean = false,
        pending: Boolean = false,
        pinnedPosition: UInt? = null,
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
                    pinnedPosition = pinnedPosition,
                    pendingConfirmation = pending,
                ),
        )
    }
}
