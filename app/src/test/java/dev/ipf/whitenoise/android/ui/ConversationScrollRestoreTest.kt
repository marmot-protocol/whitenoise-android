package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot
import dev.ipf.whitenoise.android.ui.conversation.conversationJumpToNewestTargetListIndex
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollKey
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollRestoreListIndex
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollSnapshotOnLeave
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
    fun scrollKeyHandlesNullAccountRef() {
        assertEquals(
            "\u0000group",
            conversationScrollKey(null, "group"),
        )
    }

    @Test
    fun nearBottomOnLeaveDoesNotPersistSnapshot() {
        assertNull(
            conversationScrollSnapshotOnLeave(
                firstVisibleItemIndex = 12,
                firstVisibleItemScrollOffset = 48,
                nearBottom = true,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            ),
        )
    }

    @Test
    fun readingHistoryOnLeavePersistsAnchoredSnapshot() {
        assertEquals(
            ConversationScrollSnapshot(
                firstVisibleItemIndex = 5,
                firstVisibleItemScrollOffset = 120,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            ),
            conversationScrollSnapshotOnLeave(
                firstVisibleItemIndex = 5,
                firstVisibleItemScrollOffset = 120,
                nearBottom = false,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            ),
        )
    }

    @Test
    fun restoreFromExpandedWindowPrefersStableAnchorOverSavedIndex() {
        val renderedItemIds =
            (0 until 50)
                .map { "msg:$it" }
                .toMutableList()
                .also { it[12] = "msg:reader" }
        val renderedMessageIds =
            (0 until 50)
                .map { "$it" }
                .toMutableList()
                .also { it[12] = "reader" }
        val snapshot =
            ConversationScrollSnapshot(
                firstVisibleItemIndex = 125,
                firstVisibleItemScrollOffset = 64,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            )

        assertEquals(
            1 + 1 + 12,
            conversationScrollRestoreListIndex(
                snapshot = snapshot,
                renderedItemIds = renderedItemIds,
                renderedMessageIds = renderedMessageIds,
                olderHeaderCount = 1,
            ),
        )
    }

    @Test
    fun restoreCanResolveByMessageIdWhenRowKeyChanges() {
        val snapshot =
            ConversationScrollSnapshot(
                firstVisibleItemIndex = 125,
                firstVisibleItemScrollOffset = 64,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            )

        assertEquals(
            1,
            conversationScrollRestoreListIndex(
                snapshot = snapshot,
                renderedItemIds = listOf("stream:reader"),
                renderedMessageIds = listOf("reader"),
                olderHeaderCount = 0,
            ),
        )
    }

    @Test
    fun restoreFallsBackToSavedIndexWhenAnchorIsUnavailable() {
        val snapshot =
            ConversationScrollSnapshot(
                firstVisibleItemIndex = 125,
                firstVisibleItemScrollOffset = 64,
                anchorItemId = "msg:reader",
                anchorMessageIdHex = "reader",
            )

        assertEquals(
            125,
            conversationScrollRestoreListIndex(
                snapshot = snapshot,
                renderedItemIds = (0 until 50).map { "msg:$it" },
                olderHeaderCount = 1,
            ),
        )
    }

    @Test
    fun jumpToNewestWithUnreadTargetsLastReadAnchorFirst() {
        assertEquals(
            1 + 1 + 2,
            conversationJumpToNewestTargetListIndex(
                unreadIncomingCount = 3,
                readAnchorMessageId = "read",
                renderedMessageIds =
                    listOf(
                        "older",
                        "previous",
                        "read",
                        "unread-1",
                        "unread-2",
                        "unread-3",
                    ),
                visibleListIndices = setOf(1, 2),
                olderHeaderCount = 1,
                bottomTimelineIndex = 8,
            ),
        )
    }

    @Test
    fun jumpToNewestFromLastReadAnchorTargetsBottom() {
        assertEquals(
            8,
            conversationJumpToNewestTargetListIndex(
                unreadIncomingCount = 3,
                readAnchorMessageId = "read",
                renderedMessageIds =
                    listOf(
                        "older",
                        "previous",
                        "read",
                        "unread-1",
                        "unread-2",
                        "unread-3",
                    ),
                visibleListIndices = setOf(4, 5, 6),
                olderHeaderCount = 1,
                bottomTimelineIndex = 8,
            ),
        )
    }

    @Test
    fun jumpToNewestWithoutReadableAnchorTargetsBottom() {
        assertEquals(
            8,
            conversationJumpToNewestTargetListIndex(
                unreadIncomingCount = 3,
                readAnchorMessageId = "missing",
                renderedMessageIds =
                    listOf(
                        "older",
                        "previous",
                        "read",
                        "unread-1",
                        "unread-2",
                        "unread-3",
                    ),
                visibleListIndices = setOf(1, 2),
                olderHeaderCount = 1,
                bottomTimelineIndex = 8,
            ),
        )
        assertEquals(
            8,
            conversationJumpToNewestTargetListIndex(
                unreadIncomingCount = 0,
                readAnchorMessageId = "read",
                renderedMessageIds = listOf("older", "previous", "read"),
                visibleListIndices = setOf(1, 2),
                olderHeaderCount = 1,
                bottomTimelineIndex = 8,
            ),
        )
    }
}
