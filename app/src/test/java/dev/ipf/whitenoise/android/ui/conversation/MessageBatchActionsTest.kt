package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MessageBatchActionsTest {
    @Test
    fun submissionGuardRejectsRecompositionDoubleSubmitUntilCompletion() {
        val guard = BatchDeleteSubmissionGuard()

        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())
        guard.finish()
        assertTrue(guard.tryStart())
    }

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
    fun forwardPayloadsAcceptAttachmentOnlyAndPreserveMixedTimelineOrder() {
        val text = ForwardMessagePayload.Text("source", "text", "before")
        val media =
            ForwardMessagePayload.Media(
                sourceGroupIdHex = "source",
                sourceMessageIdHex = "media",
                caption = "caption",
                attachments = listOf(ForwardAttachmentSource(0, mediaReference("photo.jpg"))),
            )
        val captionless =
            ForwardMessagePayload.Media(
                sourceGroupIdHex = "source",
                sourceMessageIdHex = "file",
                caption = null,
                attachments = listOf(ForwardAttachmentSource(0, mediaReference("notes.pdf"))),
            )
        val selected =
            listOf(
                actionItem("text", forwardPayload = text),
                actionItem("media", forwardPayload = media),
                actionItem("file", forwardPayload = captionless),
            )

        assertEquals(listOf(text, media, captionless), batchForwardPayloads(selected))
        assertTrue(batchSelectionActionAvailability(selected, ComposerGate.COMPOSER).canForward)
    }

    @Test
    fun oneBlockedAttachmentDisablesTheWholeForwardBatch() {
        val media =
            ForwardMessagePayload.Media(
                sourceGroupIdHex = "source",
                sourceMessageIdHex = "media",
                caption = null,
                attachments = listOf(ForwardAttachmentSource(0, mediaReference("photo.jpg"))),
            )
        val selected =
            listOf(
                actionItem("media", forwardPayload = media),
                actionItem("pending", blockedReason = ForwardBlockedReason.PendingAttachment),
            )

        assertTrue(batchForwardPayloads(selected).isEmpty())
        assertFalse(batchSelectionActionAvailability(selected, ComposerGate.COMPOSER).canForward)
    }

    @Test
    fun explicitBlockedStateCannotFallBackToLegacyTextBody() {
        val blocked =
            BatchMessageActionItem(
                messageId = "failed",
                senderId = "alice",
                senderDisplayName = "Alice",
                copyableText = null,
                forwardableText = "must not escape",
                canDeleteForEveryone = false,
                forwardBlockedReason = ForwardBlockedReason.Unsupported,
            )

        assertTrue(batchForwardPayloads(listOf(blocked)).isEmpty())
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
                    attempts = batchDeleteAttempts(selections, BatchDeleteScope.EVERYONE),
                    deleteForEveryone = { record ->
                        protocolDeletes += record.messageIdHex
                        if (record.messageIdHex == "everyone-fail") {
                            Result.failure(SecurityException("stale capability"))
                        } else {
                            Result.success(Unit)
                        }
                    },
                    hideLocally = { messageId ->
                        localHides += messageId
                        Result.success(Unit)
                    },
                )

            assertEquals(3, result.attempted)
            assertEquals(2, result.succeeded)
            assertEquals(listOf("everyone-fail"), result.failedAttempts.map { it.selection.action.messageId })
            assertEquals(
                BatchDeleteOperationKind.DeleteForEveryone,
                result.failures
                    .single()
                    .attempt.operation,
            )
            assertEquals(BatchDeleteFailureCategory.PermissionDenied, result.failures.single().failure)
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
                    attempts = batchDeleteAttempts(selections, BatchDeleteScope.LOCAL_ONLY),
                    deleteForEveryone = { record ->
                        protocolDeletes += record.messageIdHex
                        Result.success(Unit)
                    },
                    hideLocally = { messageId ->
                        localHides += messageId
                        Result.success(Unit)
                    },
                )

            assertEquals(2, result.attempted)
            assertEquals(2, result.succeeded)
            assertTrue(result.failures.isEmpty())
            assertEquals(emptyList<String>(), protocolDeletes)
            assertEquals(listOf("mine", "other"), localHides)
        }

    @Test
    fun retryStateRetainsOnlyFailuresAndRetryDoesNotRepeatSuccesses() =
        runBlocking {
            val selections =
                listOf(
                    selection("group-ok", 100uL, 1uL, canDeleteForEveryone = true),
                    selection("local-fail", 200uL, 2uL),
                    selection("group-fail", 300uL, 3uL, canDeleteForEveryone = true),
                )
            val first =
                executeBatchDelete(
                    attempts = batchDeleteAttempts(selections, BatchDeleteScope.EVERYONE),
                    deleteForEveryone = { record ->
                        if (record.messageIdHex == "group-ok") Result.success(Unit) else Result.failure(IOException())
                    },
                    hideLocally = { Result.failure(IllegalStateException()) },
                )
            val initialState = BatchDeleteRetryState.from(first)
            val retriedGroup = mutableListOf<String>()
            val retriedLocal = mutableListOf<String>()

            val retry =
                executeBatchDelete(
                    attempts = initialState.failedAttempts,
                    deleteForEveryone = { record ->
                        retriedGroup += record.messageIdHex
                        Result.success(Unit)
                    },
                    hideLocally = { messageId ->
                        retriedLocal += messageId
                        Result.success(Unit)
                    },
                )
            val finalState = initialState.afterRetry(retry)

            assertEquals(listOf("group-fail"), retriedGroup)
            assertEquals(listOf("local-fail"), retriedLocal)
            assertEquals(3, finalState.succeeded)
            assertTrue(finalState.failures.isEmpty())
        }

    @Test
    fun totalFailureRetainsEveryAttemptAndReportsKinds() =
        runBlocking {
            val selections =
                listOf(
                    selection("group", 100uL, 1uL, canDeleteForEveryone = true),
                    selection("local", 200uL, 2uL),
                )
            val result =
                executeBatchDelete(
                    attempts = batchDeleteAttempts(selections, BatchDeleteScope.EVERYONE),
                    deleteForEveryone = { Result.failure(SecurityException()) },
                    hideLocally = { Result.failure(IOException()) },
                )
            val state = BatchDeleteRetryState.from(result)

            assertEquals(0, state.succeeded)
            assertEquals(2, state.failedAttempts.size)
            assertEquals(1, state.failedGroupDeletes)
            assertEquals(1, state.failedLocalHides)
        }

    @Test
    fun cancellationEscapesWithoutBecomingAFailureOutcome() =
        runBlocking {
            val completed = mutableListOf<BatchDeleteOperationOutcome>()
            try {
                executeBatchDelete(
                    attempts =
                        batchDeleteAttempts(
                            listOf(
                                selection("committed", 100uL, 1uL),
                                selection("cancel", 200uL, 2uL),
                            ),
                            BatchDeleteScope.LOCAL_ONLY,
                        ),
                    deleteForEveryone = { Result.success(Unit) },
                    hideLocally = { messageId ->
                        if (messageId == "committed") {
                            Result.success(Unit)
                        } else {
                            Result.failure(CancellationException("screen left"))
                        }
                    },
                    onOutcome = completed::add,
                )
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(listOf("committed"), completed.map { it.attempt.selection.action.messageId })
            }
        }

    @Test
    fun diagnosticReportIsBoundedAndContainsNoMessageDataOrRawFailure() =
        runBlocking {
            val fullId = "ab".repeat(32)
            val selection = selection(fullId, 100uL, 1uL, canDeleteForEveryone = true)
            val result =
                executeBatchDelete(
                    attempts = batchDeleteAttempts(listOf(selection), BatchDeleteScope.EVERYONE),
                    deleteForEveryone = { Result.failure(IOException("secret body https://user:pass@example.com")) },
                    hideLocally = { Result.success(Unit) },
                )

            val report = batchDeleteDiagnosticReport(BatchDeleteRetryState.from(result))

            assertTrue(report.length <= 600)
            assertTrue("operation=MESSAGE_BATCH_DELETE" in report)
            assertTrue("DELETE_FOR_EVERYONE.CONNECTIVITY=1" in report)
            assertFalse(fullId in report)
            assertFalse("secret body" in report)
            assertFalse("user:pass" in report)
            assertFalse("IOException" in report)
        }

    private fun actionItem(
        id: String,
        forwardPayload: ForwardMessagePayload? = null,
        blockedReason: ForwardBlockedReason? = null,
    ) = BatchMessageActionItem(
        messageId = id,
        senderId = "alice",
        senderDisplayName = "Alice",
        copyableText = null,
        forwardableText = null,
        canDeleteForEveryone = false,
        forwardPayload = forwardPayload,
        forwardBlockedReason = blockedReason,
    )

    private fun mediaReference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "application/octet-stream",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 7uL,
            dim = null,
            thumbhash = null,
        )

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
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = recordedAt,
                    receivedAt = recordedAt,
                ),
            status = MessageStatus.Received,
            timelineOrder = timelineOrder,
        )
}
