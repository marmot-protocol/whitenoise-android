package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import kotlinx.coroutines.runBlocking
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
                BatchMessageActionItem("m1", "alice", "Alice", "caption", null, canDeleteForEveryone = false),
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
                BatchMessageActionItem("m1", "alice", "Alice", "hi", "hi", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "bob", "Bob", null, null, canDeleteForEveryone = false),
                BatchMessageActionItem("m3", "bob", "Bob", "hey", "hey", canDeleteForEveryone = false),
            )

        assertEquals("Alice: hi\nBob: hey", batchCopyText(selected))
    }

    @Test
    fun copyOmitsSenderPrefixWhenOtherSendersHaveNoCopyableText() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "hello", "hello", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "bob", "Bob", null, null, canDeleteForEveryone = false),
            )

        assertEquals("hello", batchCopyText(selected))
    }

    @Test
    fun copyOmitsSenderPrefixWhenEverySelectedMessageHasSameSender() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", " first ", "first", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "ALICE", "Alice", "second", "second", canDeleteForEveryone = false),
            )

        assertEquals("first\nsecond", batchCopyText(selected))
    }

    @Test
    fun forwardBodiesDisableTheEntireBatchWhenAnySelectedMessageIsUnsupported() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", " one ", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "alice", "Alice", "caption", null, canDeleteForEveryone = false),
                BatchMessageActionItem("m3", "alice", "Alice", "one", "one", canDeleteForEveryone = false),
            )

        assertEquals(emptyList<String>(), batchForwardBodies(selected))
    }

    @Test
    fun forwardBodiesPreserveVerbatimTextTimelineOrderAndDuplicates() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", " one ", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "alice", "Alice", "one", "one", canDeleteForEveryone = false),
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
                deletedMessageIds = emptySet(),
                invalidVisibleMessageIds = emptySet(),
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
                deletedMessageIds = setOf(deletedOffscreen.action.messageId),
                invalidVisibleMessageIds = setOf(invalidVisible.action.messageId),
            )

        assertTrue(retained.isEmpty())
    }

    @Test
    fun reconciliationRetainsOffscreenSelectionsWithoutCapEvictionBookkeeping() {
        val offscreen = selection("offscreen", recordedAt = 100uL, timelineOrder = 1uL)

        val retained =
            reconcileBatchSelections(
                selected = mapOf(offscreen.action.messageId to offscreen),
                selectableVisible = emptyMap(),
                deletedMessageIds = emptySet(),
                invalidVisibleMessageIds = emptySet(),
            )

        assertEquals(mapOf(offscreen.action.messageId to offscreen), retained)
    }

    @Test
    fun deleteBreakdownCountsForEveryoneCapableAgainstLocalOnly() {
        val selected =
            listOf(
                // Own message and an admin-moderatable other both count as
                // for-everyone; a non-moderatable other is local-only.
                BatchMessageActionItem("m1", "me", "Me", "one", "one", canDeleteForEveryone = true),
                BatchMessageActionItem("m2", "alice", "Alice", "two", "two", canDeleteForEveryone = false),
                BatchMessageActionItem("m3", "carol", "Carol", null, null, canDeleteForEveryone = true),
            )

        val breakdown = batchDeleteBreakdown(selected)
        assertEquals(BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 1), breakdown)
        assertTrue(breakdown.canOfferDeleteForEveryone)
    }

    @Test
    fun deleteBreakdownWithNoForEveryoneCapableOffersLocalOnly() {
        val selected =
            listOf(
                BatchMessageActionItem("m1", "alice", "Alice", "one", "one", canDeleteForEveryone = false),
                BatchMessageActionItem("m2", "bob", "Bob", "two", "two", canDeleteForEveryone = false),
            )

        val breakdown = batchDeleteBreakdown(selected)
        assertEquals(BatchDeleteBreakdown(deleteForEveryone = 0, hideLocally = 2), breakdown)
        assertFalse(breakdown.canOfferDeleteForEveryone)
    }

    @Test
    fun executeBatchDeleteEveryoneScopeRoutesByCapabilityAndAggregatesFailures() =
        runBlocking {
            val selections =
                listOf(
                    selection("everyone-ok", recordedAt = 100uL, timelineOrder = 1uL, canDeleteForEveryone = true),
                    selection("other", recordedAt = 200uL, timelineOrder = 2uL),
                    selection("everyone-fail", recordedAt = 300uL, timelineOrder = 3uL, canDeleteForEveryone = true),
                )
            val protocolDeletes = mutableListOf<String>()
            val localHides = mutableListOf<String>()

            val result =
                executeBatchDelete(
                    selections = selections,
                    scope = BatchDeleteScope.EVERYONE,
                    deleteForEveryone = { record ->
                        protocolDeletes += record.messageIdHex
                        record.messageIdHex != "everyone-fail"
                    },
                    hideLocally = { messageId ->
                        localHides += messageId
                        true
                    },
                )

            assertEquals(BatchDeleteResult(attempted = 3, succeeded = 2), result)
            assertEquals(listOf("everyone-ok", "everyone-fail"), protocolDeletes)
            assertEquals(listOf("other"), localHides)
        }

    @Test
    fun executeBatchDeleteLocalOnlyScopeHidesEveryMessageAndPublishesNothing() =
        runBlocking {
            val selections =
                listOf(
                    selection("mine", recordedAt = 100uL, timelineOrder = 1uL, canDeleteForEveryone = true),
                    selection("other", recordedAt = 200uL, timelineOrder = 2uL),
                )
            val protocolDeletes = mutableListOf<String>()
            val localHides = mutableListOf<String>()

            val result =
                executeBatchDelete(
                    selections = selections,
                    scope = BatchDeleteScope.LOCAL_ONLY,
                    deleteForEveryone = { record ->
                        protocolDeletes += record.messageIdHex
                        true
                    },
                    hideLocally = { messageId ->
                        localHides += messageId
                        true
                    },
                )

            assertEquals(BatchDeleteResult(attempted = 2, succeeded = 2), result)
            assertEquals(emptyList<String>(), protocolDeletes)
            assertEquals(listOf("mine", "other"), localHides)
        }

    private fun selection(
        id: String,
        recordedAt: ULong,
        timelineOrder: ULong,
        canDeleteForEveryone: Boolean = false,
    ): BatchMessageSelection =
        BatchMessageSelection(
            action = BatchMessageActionItem(id, "alice", "Alice", id, id, canDeleteForEveryone = canDeleteForEveryone),
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
