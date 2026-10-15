package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListHeadSnapTest {
    @Test
    fun headReorderSnapsWhenReaderWasAtTopWithClippedOffset() {
        assertTrue(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = 0,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderSnapsWhenReaderWasFlushAtTop() {
        // Flush-at-top is not "scrolled deeper": at the exact top the reader
        // is watching the head, and a key-anchored reorder renders the new
        // head above the viewport where it would otherwise stay invisible.
        assertTrue(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = 0,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderDoesNotSnapWhenReaderWasScrolledDeeperBeforeReorder() {
        assertFalse(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = 4,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderDecidesOnPreReorderStateNotTheReanchoredSnapshot() {
        // The key-anchored reorder scenario: pre-reorder the reader sat at
        // index 0 / offset 0; post-reorder the list reads index 1 / offset 0
        // because LazyColumn kept the old head's row pinned by key. The
        // decision must use the pre-reorder index (0 → snap), not the
        // re-anchored one (1 → would never snap).
        val preReorderIndex = 0
        val postReorderIndex = 1
        assertTrue(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = preReorderIndex,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
        assertFalse(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = postReorderIndex,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderDoesNotSnapWithoutAHeadChange() {
        assertFalse(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "a",
                preReorderFirstVisibleItemIndex = 0,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderDoesNotSnapWhileUserIsScrolling() {
        assertFalse(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = 0,
                isScrollInProgress = true,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun headReorderDoesNotSnapOnArchivedList() {
        assertFalse(
            shouldSnapChatListForHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                preReorderFirstVisibleItemIndex = 0,
                isScrollInProgress = false,
                isActiveList = false,
            ),
        )
    }

    /** A different absolute head after the conversation snaps the returning list to the top (#1313). */
    @Test
    fun conversationReturnSnapsWhenHeadChangedWhileAway() {
        assertTrue(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("a"),
                currentHead = head("b"),
                isActiveList = true,
            ),
        )
    }

    /** A send that promotes a lower chat beneath an unchanged pinned head still snaps to the top (#3280). */
    @Test
    fun conversationReturnSnapsWhenUnpinnedHeadChangedBeneathUnchangedPinnedHead() {
        assertTrue(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("pinned", activity = "a"),
                currentHead = head("pinned", activity = "c"),
                isActiveList = true,
            ),
        )
    }

    /** A first unpinned chat arriving below an all-pinned list is a promotion worth returning to (#3280). */
    @Test
    fun conversationReturnSnapsWhenUnpinnedChatAppearsBelowAllPinnedList() {
        assertTrue(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("pinned", activity = null),
                currentHead = head("pinned", activity = "c"),
                isActiveList = true,
            ),
        )
    }

    /** An unchanged pinned head and unpinned head keep the restored viewport. */
    @Test
    fun conversationReturnDoesNotSnapWhenPinnedAndUnpinnedHeadsUnchanged() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("pinned", activity = "a"),
                currentHead = head("pinned", activity = "a"),
                isActiveList = true,
            ),
        )
    }

    /** An unchanged visible head keeps the restored viewport. */
    @Test
    fun conversationReturnDoesNotSnapWhenVisibleHeadUnchanged() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("filtered-head"),
                currentHead = head("filtered-head"),
                isActiveList = true,
            ),
        )
    }

    /** A controller-only reorder that leaves the filtered visible head in place does not snap (#1313). */
    @Test
    fun conversationReturnDoesNotSnapWhenVisibleHeadUnchangedDespiteControllerReorder() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("filtered-b"),
                currentHead = head("filtered-b"),
                isActiveList = true,
            ),
        )
    }

    /** Without a head captured at open, an active reader is never yanked. */
    @Test
    fun conversationReturnDoesNotSnapForActiveReaderWithoutOpenSnapshot() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = null,
                currentHead = head("b"),
                isActiveList = true,
            ),
        )
    }

    /** The archived list never snaps on return. */
    @Test
    fun conversationReturnDoesNotSnapOnArchivedList() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = head("a"),
                currentHead = head("b"),
                isActiveList = false,
            ),
        )
    }

    /** The decision waits while the active list has no head yet. */
    @Test
    fun conversationReturnCannotDecideWithNullCurrentHeadOnActiveList() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headAtConversationOpen = head("a"),
                currentHead = null,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    /** The decision waits while scroll restoration is in flight. */
    @Test
    fun conversationReturnCannotDecideWhileScrollInProgressOnActiveList() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headAtConversationOpen = head("a"),
                currentHead = head("b"),
                isScrollInProgress = true,
                isActiveList = true,
            ),
        )
    }

    /** The decision runs once scrolling settles on the active list. */
    @Test
    fun conversationReturnCanDecideAfterScrollSettlesOnActiveList() {
        assertTrue(
            canDecideConversationReturnHeadSnap(
                headAtConversationOpen = head("a"),
                currentHead = head("b"),
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    /** The archived list decides immediately so the one-shot head is cleared. */
    @Test
    fun conversationReturnCanDecideOnArchivedListToClearOneShot() {
        assertTrue(
            canDecideConversationReturnHeadSnap(
                headAtConversationOpen = head("a"),
                currentHead = null,
                isScrollInProgress = true,
                isActiveList = false,
            ),
        )
    }

    /** No decision runs without a captured head. */
    @Test
    fun conversationReturnCannotDecideWithoutPendingHead() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headAtConversationOpen = null,
                currentHead = head("b"),
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    /** The captured head records the absolute first row and the first unpinned row beneath the pins. */
    @Test
    fun returnHeadCapturesAbsoluteHeadAndFirstUnpinnedRow() {
        assertEquals(
            ChatListReturnHead(headId = "pin0", activityHeadId = "a"),
            chatListReturnHead(listOf(item("pin0", pinned = true), item("pin1", pinned = true), item("a"), item("b"))),
        )
        assertEquals(
            ChatListReturnHead(headId = "a", activityHeadId = "a"),
            chatListReturnHead(listOf(item("a"), item("b"))),
        )
        assertEquals(
            ChatListReturnHead(headId = "pin0", activityHeadId = null),
            chatListReturnHead(listOf(item("pin0", pinned = true))),
        )
        assertNull(chatListReturnHead(emptyList()))
    }

    /** Returning after a send promoted lower chat C beneath pinned P snaps; returning unchanged does not (#3280). */
    @Test
    fun sendPromotionBeneathPinnedHeadSnapsOnlyWhenTheUnpinnedOrderChanged() {
        val beforeOpen = listOf(item("p", pinned = true), item("a"), item("b"), item("c"))
        val afterSend = listOf(item("p", pinned = true), item("c"), item("a"), item("b"))

        assertTrue(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = chatListReturnHead(beforeOpen),
                currentHead = chatListReturnHead(afterSend),
                isActiveList = true,
            ),
        )
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headAtConversationOpen = chatListReturnHead(beforeOpen),
                currentHead = chatListReturnHead(beforeOpen),
                isActiveList = true,
            ),
        )
    }

    /** A captured head whose first unpinned row defaults to the absolute head. */
    private fun head(
        id: String,
        activity: String? = id,
    ): ChatListReturnHead = ChatListReturnHead(headId = id, activityHeadId = activity)

    /** A projected chat row with the given id and pin state. */
    private fun item(
        id: String,
        pinned: Boolean = false,
    ): ChatListItem {
        val base = ChatRowPortFixtures.item()
        return base.copy(
            group = base.group.copy(groupIdHex = id),
            projection = base.projection!!.copy(groupIdHex = id, pinned = pinned),
        )
    }
}
