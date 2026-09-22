package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListWindowPagingTest {
    @Test
    fun shiftedFrontPagesBackwardNearTheFirstRetainedRow() {
        assertTrue(shouldPageChatListBackward(0, hasMoreBefore = true, searchActive = false, prefetchRows = 10))
        assertTrue(shouldPageChatListBackward(9, hasMoreBefore = true, searchActive = false, prefetchRows = 10))
        assertFalse(shouldPageChatListBackward(10, hasMoreBefore = true, searchActive = false, prefetchRows = 10))
        assertFalse(shouldPageChatListBackward(0, hasMoreBefore = false, searchActive = false, prefetchRows = 10))
        assertFalse(shouldPageChatListBackward(0, hasMoreBefore = true, searchActive = true, prefetchRows = 10))
    }

    @Test
    fun shiftedFrontKeepsJumpToTopVisibleAtRetainedIndexZero() {
        assertTrue(shouldShowChatListJumpToTop(0, true, false, showIndex = 5, hideIndex = 2))
        assertTrue(shouldShowChatListJumpToTop(4, false, true, showIndex = 5, hideIndex = 2))
        assertFalse(shouldShowChatListJumpToTop(2, false, true, showIndex = 5, hideIndex = 2))
    }
}
