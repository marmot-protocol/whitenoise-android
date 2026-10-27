package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListBatchTest {
    @Test fun refusedControllerAttemptKeepsPriorUnconfirmedAndFailedOutcomes() {
        val previous =
            ChatListBatchResult(
                mapOf(
                    "changed" to ChatListBatchOutcome.CHANGED,
                    "unknown" to ChatListBatchOutcome.UNCONFIRMED,
                    "failed" to ChatListBatchOutcome.FAILED,
                ),
                setOf("changed", "unknown", "failed"),
            )
        assertEquals(previous, previous.withAttempt(null))
        assertEquals(1, previous.withAttempt(null).changed)
        assertEquals(1, previous.withAttempt(null).unconfirmed)
        assertEquals(listOf("failed"), previous.withAttempt(null).retryIds)
    }

    @Test fun mixedStateCommandsAlwaysDescribeOneExplicitDestination() {
        val row =
            presentedRow("chat").copy(
                actions =
                    noChatRowActions().copy(
                        canMarkRead = true,
                        canMarkUnread = true,
                        canMute = true,
                        canUnmute = true,
                        canPin = true,
                        canUnpin = true,
                        canArchive = true,
                        canRestore = true,
                    ),
            )
        val ordinary = row.copy(row = row.row.copy(hasUnread = false, manuallyMarkedUnread = false, muted = false, pinned = false, archived = false))
        assertEquals(ChatListBatchOutcome.SATISFIED, chatListBatchDisposition(ordinary, ChatListBatchCommand.READ))
        assertNull(chatListBatchDisposition(ordinary, ChatListBatchCommand.UNREAD))
        assertNull(chatListBatchDisposition(ordinary, ChatListBatchCommand.MUTE))
        assertNull(chatListBatchDisposition(ordinary, ChatListBatchCommand.PIN))
        assertNull(chatListBatchDisposition(ordinary, ChatListBatchCommand.ARCHIVE))
        val opposite = ordinary.copy(row = ordinary.row.copy(hasUnread = true, muted = true, pinned = true, archived = true))
        listOf(ChatListBatchCommand.UNREAD, ChatListBatchCommand.MUTE, ChatListBatchCommand.PIN, ChatListBatchCommand.ARCHIVE).forEach {
            assertEquals(ChatListBatchOutcome.SATISFIED, chatListBatchDisposition(opposite, it))
        }
        listOf(ChatListBatchCommand.READ, ChatListBatchCommand.UNMUTE, ChatListBatchCommand.UNPIN, ChatListBatchCommand.RESTORE).forEach {
            assertNull(chatListBatchDisposition(opposite, it))
        }
        assertEquals(ChatListBatchOutcome.SKIPPED, chatListBatchDisposition(ordinary.copy(actions = noChatRowActions()), ChatListBatchCommand.UNREAD))
        assertEquals(ChatListBatchOutcome.SKIPPED, chatListBatchDisposition(ordinary.copy(row = ordinary.row.copy(archived = true)), ChatListBatchCommand.PIN))
    }

    @Test fun selectedRunsMoveOnePositionWithoutChangingRelativeOrders() {
        val order = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(listOf("b", "c", "a", "e", "d", "f"), moveSelectedPinnedChats(order, setOf("b", "c", "e"), -1))
        assertEquals(listOf("a", "d", "b", "c", "f", "e"), moveSelectedPinnedChats(order, setOf("b", "c", "e"), 1))
        assertEquals(order, moveSelectedPinnedChats(order, order.toSet(), -1))
        assertEquals(order, moveSelectedPinnedChats(order, emptySet(), 1))
        assertEquals(emptyList<String>(), moveSelectedPinnedChats(emptyList(), emptySet(), 1))
    }
}
