package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the conversation scroll-restore decision (issue #1107).
 *
 * Leaving a chat while reading history should persist a list index/offset for
 * the next open; leaving at/near the bottom should not, so the unread/newest
 * anchor still runs.
 */
class ConversationScrollRestoreTest {
    @Test
    fun scrollKeyCombinesAccountAndGroup() {
        assertEquals(
            "acct\u0000group",
            conversationScrollKey("acct", "group"),
        )
    }

    @Test
    fun nearBottomOnLeaveDoesNotPersistSnapshot() {
        assertNull(
            conversationScrollSnapshotOnLeave(
                firstVisibleItemIndex = 12,
                firstVisibleItemScrollOffset = 48,
                nearBottom = true,
            ),
        )
    }

    @Test
    fun readingHistoryOnLeavePersistsSnapshot() {
        assertEquals(
            ConversationScrollSnapshot(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 120),
            conversationScrollSnapshotOnLeave(
                firstVisibleItemIndex = 5,
                firstVisibleItemScrollOffset = 120,
                nearBottom = false,
            ),
        )
    }
}
