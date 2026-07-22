package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
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

    @Test
    fun conversationReturnSnapsWhenHeadChangedWhileAway() {
        assertTrue(
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = "a",
                currentHeadId = "b",
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnDoesNotSnapWhenVisibleHeadUnchanged() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = "filtered-head",
                currentHeadId = "filtered-head",
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnDoesNotSnapWhenVisibleHeadUnchangedDespiteControllerReorder() {
        // Visible-head capture/compare must not treat a controller-only reorder as
        // a head change while the filtered visible head stayed the same (#1313).
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = "filtered-b",
                currentHeadId = "filtered-b",
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnDoesNotSnapForActiveReaderWithoutOpenSnapshot() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = null,
                currentHeadId = "b",
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnDoesNotSnapOnArchivedList() {
        assertFalse(
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = "a",
                currentHeadId = "b",
                isActiveList = false,
            ),
        )
    }

    @Test
    fun conversationReturnCannotDecideWithNullCurrentHeadOnActiveList() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = "a",
                currentHeadId = null,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnCannotDecideWhileScrollInProgressOnActiveList() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = "a",
                currentHeadId = "b",
                isScrollInProgress = true,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnCanDecideAfterScrollSettlesOnActiveList() {
        assertTrue(
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = "a",
                currentHeadId = "b",
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun conversationReturnCanDecideOnArchivedListToClearOneShot() {
        assertTrue(
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = "a",
                currentHeadId = null,
                isScrollInProgress = true,
                isActiveList = false,
            ),
        )
    }

    @Test
    fun conversationReturnCannotDecideWithoutPendingHead() {
        assertFalse(
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = null,
                currentHeadId = "b",
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }
}
