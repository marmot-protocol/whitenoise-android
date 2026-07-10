package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
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
    fun forwardBodiesDisableTheEntireBatchWhenAnySelectedMessageIsUnsupported() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", " one ", mine = false),
                BatchMessageActionItem("m2", "alice", "Alice", "caption", null, mine = false),
                BatchMessageActionItem("m3", "alice", "Alice", "one", "one", mine = false),
            )

        assertEquals(emptyList<String>(), batchForwardBodies(selected))
    }

    @Test
    fun forwardBodiesPreserveVerbatimTextTimelineOrderAndDuplicates() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", " one ", mine = false),
                BatchMessageActionItem("m2", "alice", "Alice", "one", "one", mine = false),
            )

        assertEquals(listOf(" one ", "one"), batchForwardBodies(selected))
    }

    @Test
    fun reconciliationRetainsSelectionsOutsideTheVisiblePaginationWindow() {
        val recent = selection("recent", recordedAt = 200uL, timelineOrder = 2uL)
        val older = selection("older", recordedAt = 100uL, timelineOrder = 1uL)

        val retained =
            reconcileBatchSelections(
                selected = mapOf(recent.action.messageId to recent),
                selectableVisible = mapOf(older.action.messageId to older),
                capEvictedMessageIds = setOf(recent.action.messageId),
                deletedMessageIds = emptySet(),
            )

        assertEquals(setOf("recent"), retained.keys)
        assertEquals(
            listOf("older", "recent"),
            orderedBatchSelections(retained.values + older).map { it.action.messageId },
        )
    }

    @Test
    fun reconciliationPrunesOnlyVisibleInvalidOrKnownDeletedSelections() {
        val invalidVisible = selection("invalid-visible", recordedAt = 100uL, timelineOrder = 1uL)
        val deletedOffscreen = selection("deleted-offscreen", recordedAt = 200uL, timelineOrder = 2uL)

        val retained =
            reconcileBatchSelections(
                selected =
                    mapOf(
                        invalidVisible.action.messageId to invalidVisible,
                        deletedOffscreen.action.messageId to deletedOffscreen,
                    ),
                selectableVisible = emptyMap(),
                capEvictedMessageIds = setOf(deletedOffscreen.action.messageId),
                deletedMessageIds = setOf(deletedOffscreen.action.messageId),
            )

        assertTrue(retained.isEmpty())
    }

    @Test
    fun reconciliationPrunesOffscreenSelectionsNotEvictedByTheTimelineCap() {
        val removed = selection("removed", recordedAt = 100uL, timelineOrder = 1uL)

        val retained =
            reconcileBatchSelections(
                selected = mapOf(removed.action.messageId to removed),
                selectableVisible = emptyMap(),
                capEvictedMessageIds = emptySet(),
                deletedMessageIds = emptySet(),
            )

        assertTrue(retained.isEmpty())
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

    private fun selection(
        id: String,
        recordedAt: ULong,
        timelineOrder: ULong,
    ): BatchMessageSelection =
        BatchMessageSelection(
            action = BatchMessageActionItem(id, "alice", "Alice", id, id, mine = false),
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = id,
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    tags = emptyList(),
                    recordedAt = recordedAt,
                    receivedAt = recordedAt,
                ),
            timelineOrder = timelineOrder,
        )
}
