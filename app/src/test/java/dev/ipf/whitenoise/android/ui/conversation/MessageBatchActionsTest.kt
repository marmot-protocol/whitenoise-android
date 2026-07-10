package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBatchActionsTest {
    @Test
    fun systemEventsAndDeletedMessagesAreNeverBatchSelectable() {
        assertTrue(
            isBatchSelectableMessage(
                messageId = "m1",
                userVisibleMessage = true,
                committedMessage = true,
                projectedDeleted = false,
                deletedMessageIds = emptySet(),
            ),
        )
        assertFalse(
            isBatchSelectableMessage(
                messageId = "m1",
                userVisibleMessage = true,
                committedMessage = true,
                projectedDeleted = true,
                deletedMessageIds = emptySet(),
            ),
        )
        assertFalse(
            isBatchSelectableMessage(
                messageId = "m1",
                userVisibleMessage = true,
                committedMessage = true,
                projectedDeleted = false,
                deletedMessageIds = setOf("m1"),
            ),
        )
        assertFalse(
            isBatchSelectableMessage(
                messageId = "m1",
                userVisibleMessage = false,
                committedMessage = true,
                projectedDeleted = false,
                deletedMessageIds = emptySet(),
            ),
        )
        assertFalse(
            isBatchSelectableMessage(
                messageId = "temp-id",
                userVisibleMessage = true,
                committedMessage = false,
                projectedDeleted = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun forwardSheetClosesWhenSelectedRowsLoseTheirLastForwardableBody() {
        val selectedRowsStillPresent =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "caption", null, mine = false),
            )

        val afterEligibilityLost =
            batchForwardSheetOpenForBodies(
                currentlyOpen = true,
                forwardBodies = batchForwardBodies(selectedRowsStillPresent),
            )

        assertFalse(afterEligibilityLost)
        assertFalse(
            batchForwardSheetOpenForBodies(
                currentlyOpen = afterEligibilityLost,
                forwardBodies = listOf("eligible again"),
            ),
        )
    }

    @Test
    fun copyKeepsSendOrderPrefixesMultipleSendersAndSkipsNonText() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "hi", "hi", mine = false),
                BatchMessageActionItem("m2", "bob", "Bob", null, null, mine = false),
                BatchMessageActionItem("m3", "bob", "Bob", "hey", "hey", mine = false),
            )

        assertEquals("Alice: hi\nBob: hey", batchCopyText(selected))
    }

    @Test
    fun copyOmitsSenderPrefixWhenOtherSendersHaveNoCopyableText() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "hello", "hello", mine = false),
                BatchMessageActionItem("m2", "bob", "Bob", null, null, mine = false),
            )

        assertEquals("hello", batchCopyText(selected))
    }

    @Test
    fun copyOmitsSenderPrefixWhenEverySelectedMessageHasSameSender() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", " first ", "first", mine = false),
                BatchMessageActionItem("m2", "ALICE", "Alice", "second", "second", mine = false),
            )

        assertEquals("first\nsecond", batchCopyText(selected))
    }

    @Test
    fun forwardBodiesSkipUnsupportedMessagesWithoutReorderingOrDeduplicating() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", " one ", mine = false),
                BatchMessageActionItem("m2", "alice", "Alice", "caption", null, mine = false),
                BatchMessageActionItem("m3", "alice", "Alice", "one", "one", mine = false),
            )

        assertEquals(listOf("one", "one"), batchForwardBodies(selected))
    }

    @Test
    fun deleteBreakdownSeparatesOwnMessagesFromLocalHides() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "me", "Me", "one", "one", mine = true),
                BatchMessageActionItem("m2", "alice", "Alice", "two", "two", mine = false),
                BatchMessageActionItem("m3", "me", "Me", null, null, mine = true),
            )

        assertEquals(
            BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 1),
            batchDeleteBreakdown(selected),
        )
    }
}
