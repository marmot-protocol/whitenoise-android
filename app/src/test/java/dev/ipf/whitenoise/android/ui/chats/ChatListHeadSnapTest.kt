package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListHeadSnapTest {
    @Test
    fun clippedHeadReorderSnapsAtItemZeroWithOffset() {
        assertTrue(
            shouldSnapChatListForClippedHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 12,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun clippedHeadReorderDoesNotSnapWhenReaderScrolledDeeper() {
        assertFalse(
            shouldSnapChatListForClippedHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                firstVisibleItemIndex = 4,
                firstVisibleItemScrollOffset = 0,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun clippedHeadReorderDoesNotSnapWhenHeadFlushAtTop() {
        assertFalse(
            shouldSnapChatListForClippedHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                isScrollInProgress = false,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun clippedHeadReorderDoesNotSnapWhileUserIsScrolling() {
        assertFalse(
            shouldSnapChatListForClippedHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 24,
                isScrollInProgress = true,
                isActiveList = true,
            ),
        )
    }

    @Test
    fun clippedHeadReorderDoesNotSnapOnArchivedList() {
        assertFalse(
            shouldSnapChatListForClippedHeadReorder(
                previousHeadId = "a",
                currentHeadId = "b",
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 24,
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
