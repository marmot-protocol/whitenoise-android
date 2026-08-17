package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimisticMessageReconciliationTest {
    @Test
    fun pendingProjectionKeepsOptimisticRetentionUntilOuterProjectionArrives() {
        val projected = message("confirmed")

        assertEquals(
            30uL,
            preservePendingProjectionRetention(
                projected = projected,
                sourceMessageIdHex = null,
                fallbackRetentionSeconds = 30uL,
            ).retentionSeconds,
        )
        assertNull(
            preservePendingProjectionRetention(
                projected = projected,
                sourceMessageIdHex = "outer-message-id",
                fallbackRetentionSeconds = 30uL,
            ).retentionSeconds,
        )
    }

    @Test
    fun pendingProjectionNeverOverridesAnEngineRetentionValue() {
        val projected = message("confirmed").copy(retentionSeconds = 60uL)

        assertEquals(
            60uL,
            preservePendingProjectionRetention(
                projected = projected,
                sourceMessageIdHex = null,
                fallbackRetentionSeconds = 30uL,
            ).retentionSeconds,
        )
    }

    @Test
    fun matchingPendingMessageIsReconciledWhenProjectionArrives() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertEquals(
            "temp",
            optimisticMessageIdForProjection(listOf(pending), message("confirmed")),
        )
    }

    @Test
    fun sentOptimisticMessageIsReconciledWhenProjectionArrivesAfterResponse() {
        val sent = timelineMessage("sent", MessageStatus.Sent)

        assertEquals(
            "sent",
            optimisticMessageIdForProjection(listOf(sent), message("confirmed")),
        )
    }

    @Test
    fun textSendSummaryWaitsOnlyWhileTempBubbleStillExists() {
        assertEquals(true, textSendAwaitingEchoConfirmation(emptyList(), optimisticStillPresent = true))
        assertEquals(false, textSendAwaitingEchoConfirmation(emptyList(), optimisticStillPresent = false))
        assertEquals(false, textSendAwaitingEchoConfirmation(listOf("confirmed-id"), optimisticStillPresent = true))
    }

    @Test
    fun retryEmptySummaryRetainsTempKeyedSentBubbleAndMessageById() {
        val tempId = "temp-retry"
        val key = "msg:$tempId"
        val record = message(tempId, plaintext = "retry me")
        val optimisticMessages = linkedMapOf(key to timelineMessage(tempId, MessageStatus.Pending, plaintext = "retry me"))
        val messageById = linkedMapOf(tempId to record)

        val reconciliation =
            reconcileSuccessfulTextSend(
                summaryMessageIds = emptyList(),
                optimisticKey = key,
                tempId = tempId,
                optimisticRecord = record,
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                projectedMessageIds = emptySet(),
                timelineOrder = 7uL,
            )

        assertEquals(true, reconciliation.awaitingEcho)
        assertEquals(tempId, reconciliation.confirmedId)
        val sent = optimisticMessages[key]
        assertEquals(MessageStatus.Sent, sent?.status)
        assertEquals(tempId, sent?.record?.messageIdHex)
        assertEquals(7uL, sent?.timelineOrder)
        assertEquals(record.copy(messageIdHex = tempId), messageById[tempId])
        assertEquals(1, optimisticMessages.size)
        assertEquals(key, optimisticMessages.keys.single())
    }

    @Test
    fun retryEmptySummaryAfterEchoReconcileDoesNotRecreateTempState() {
        val tempId = "temp-echoed"
        val key = "msg:$tempId"
        val record = message(tempId, plaintext = "already echoed")
        val optimisticMessages = linkedMapOf<String, TimelineMessage>()
        val messageById = linkedMapOf<String, AppMessageRecordFfi>()

        val reconciliation =
            reconcileSuccessfulTextSend(
                summaryMessageIds = emptyList(),
                optimisticKey = key,
                tempId = tempId,
                optimisticRecord = record,
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                projectedMessageIds = setOf("engine-id"),
                timelineOrder = 3uL,
            )

        assertEquals(false, reconciliation.awaitingEcho)
        assertEquals(false, reconciliation.insertedSent)
        assertEquals(emptyMap<String, TimelineMessage>(), optimisticMessages)
        assertEquals(emptyMap<String, AppMessageRecordFfi>(), messageById)
    }

    @Test
    fun retryConfirmedIdTransitionsToSentWithoutStaleTempRecords() {
        val tempId = "temp-confirmed"
        val key = "msg:$tempId"
        val record = message(tempId, plaintext = "confirmed retry")
        val optimisticMessages = linkedMapOf(key to timelineMessage(tempId, MessageStatus.Pending, plaintext = "confirmed retry"))
        val messageById = linkedMapOf(tempId to record)

        val reconciliation =
            reconcileSuccessfulTextSend(
                summaryMessageIds = listOf("confirmed-id"),
                optimisticKey = key,
                tempId = tempId,
                optimisticRecord = record,
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                projectedMessageIds = emptySet(),
                timelineOrder = 11uL,
            )

        assertEquals("confirmed-id", reconciliation.confirmedId)
        assertEquals(false, reconciliation.awaitingEcho)
        assertNull(optimisticMessages[key])
        assertNull(messageById[tempId])
        val sent = optimisticMessages["msg:confirmed-id"]
        assertEquals(MessageStatus.Sent, sent?.status)
        assertEquals("confirmed-id", sent?.record?.messageIdHex)
        assertEquals(record.copy(messageIdHex = "confirmed-id"), messageById["confirmed-id"])
    }

    @Test
    fun sentOptimisticReplacementIsSkippedWhenProjectionArrivesBeforeResponse() {
        assertEquals(
            false,
            shouldInsertSentOptimisticMessage("confirmed", setOf("confirmed")),
        )
        assertEquals(
            true,
            shouldInsertSentOptimisticMessage("confirmed", emptySet()),
        )
    }

    @Test
    fun queuedPendingMessagesReconcileOnlyTheMatchingProjection() {
        val first = timelineMessage("first-temp", MessageStatus.Pending, plaintext = "first")
        val second = timelineMessage("second-temp", MessageStatus.Pending, plaintext = "second")

        assertEquals(
            "first-temp",
            optimisticMessageIdForProjection(
                listOf(first, second),
                message("first-confirmed", plaintext = "first"),
            ),
        )
    }

    @Test
    fun delayedQueuedProjectionCanReconcileAfterWorkerWait() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertEquals(
            "temp",
            optimisticMessageIdForProjection(
                listOf(pending),
                message("confirmed", recordedAt = 10uL),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun delayedHistoricalSnapshotDoesNotConsumeNewerPendingMessage() {
        val pending = timelineMessage("temp", MessageStatus.Pending, recordedAt = 10uL)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("historical", recordedAt = 1uL),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun failedAndDifferentPendingMessagesAreNotReconciled() {
        val failed = timelineMessage("failed", MessageStatus.Failed)
        val different = timelineMessage("different", MessageStatus.Pending, plaintext = "another")

        assertNull(
            optimisticMessageIdForProjection(
                listOf(failed, different),
                message("confirmed"),
            ),
        )
    }

    @Test
    fun delayedProjectionReconcilesFailedOptimisticSend() {
        val failed = timelineMessage("temp", MessageStatus.Failed, plaintext = "eventually sent")

        assertEquals(
            "temp",
            optimisticMessageIdForProjection(
                listOf(failed),
                message("confirmed", plaintext = "eventually sent"),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun invalidatedProjectionMatchesRetainedFailedOptimisticSend() {
        val failed =
            timelineMessage(
                "temp",
                MessageStatus.Failed,
                plaintext = "never reached",
                recordedAt = 1uL,
            )

        assertEquals(
            "temp",
            failedOptimisticMessageIdForInvalidatedProjection(
                listOf(failed),
                message("invalidated", plaintext = "never reached", recordedAt = 99uL),
            ),
        )
    }

    @Test
    fun sentMessageFindsMatchingInvalidatedProjectionForCleanup() {
        val invalidated =
            timelineRecord(
                messageIdHex = "invalidated",
                plaintext = "retry cleaned me up",
                recordedAt = 99uL,
                invalidationStatus = "LosingBranch",
            )
        val unrelated =
            timelineRecord(
                messageIdHex = "unrelated",
                plaintext = "keep me",
                invalidationStatus = "LosingBranch",
            )

        assertEquals(
            listOf("invalidated"),
            invalidatedProjectionIdsMatchingMessage(
                mapOf(
                    invalidated.messageIdHex to invalidated,
                    unrelated.messageIdHex to unrelated,
                ),
                message("confirmed", plaintext = "retry cleaned me up"),
            ),
        )
    }

    @Test
    fun failedMessageFindsMatchingUnpublishedProjectionForSuppression() {
        val unpublished =
            timelineRecord(
                messageIdHex = "local-commit",
                plaintext = "offline duplicate",
                sourceMessageIdHex = null,
            )
        val published =
            timelineRecord(
                messageIdHex = "published",
                plaintext = "offline duplicate",
                sourceMessageIdHex = "relay-event",
            )

        assertEquals(
            listOf("local-commit"),
            unpublishedProjectionIdsMatchingMessage(
                mapOf(
                    unpublished.messageIdHex to unpublished,
                    published.messageIdHex to published,
                ),
                message("temp", plaintext = "offline duplicate"),
                activeAccountIdHex = "alice",
            ),
        )
    }

    @Test
    fun failedMessageMatchingIgnoresSenderHexCase() {
        val invalidated =
            timelineRecord(
                messageIdHex = "invalidated",
                plaintext = "case drift",
                sender = "ALICE",
                invalidationStatus = "LosingBranch",
            )

        assertEquals(
            listOf("invalidated"),
            invalidatedProjectionIdsMatchingMessage(
                mapOf(invalidated.messageIdHex to invalidated),
                message("confirmed", plaintext = "case drift", sender = "alice"),
            ),
        )
    }

    @Test
    fun delayedMediaProjectionDoesNotShapeMatchWhenFailedSiblingIsAmbiguous() {
        val failedA = mediaPending("temp-a", filename = "a.pdf").copy(status = MessageStatus.Failed)
        val pendingB = mediaPending("temp-b", filename = "b.pdf")

        assertNull(
            optimisticMessageIdForProjection(
                listOf(failedA, pendingB),
                mediaProjection("confirmed-a"),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun historicalMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("historical", recordedAt = 10uL),
            ),
        )
    }

    @Test
    fun receivedMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("received", direction = "received"),
            ),
        )
    }

    @Test
    fun multiMediaSendReconcilesByBridgeIdNotBySiblingHeuristic() {
        // Reproduction for a multi-document send where 3 docs are queued in
        // rapid succession (same direction/sender/kind/recordedAt) and each
        // optimistic carries a `_media_pending` shape. After the FIRST upload
        // confirms, performMediaUpload inserts a "bridge" optimistic keyed at
        // the confirmed event id. The relay then echoes back the kind-9
        // projection. The reconciler MUST return that bridge — not a sibling
        // pending — otherwise the wrong sibling gets removed and the user
        // sees pending bubbles vanish until each upload confirms in turn.
        val pendingB = mediaPending("temp-b", filename = "b.pdf")
        val pendingC = mediaPending("temp-c", filename = "c.pdf")
        val bridgeA = mediaSent("confirmed-a", filename = "a.pdf")
        val projection = mediaProjection("confirmed-a")

        // Bridge is inserted LAST (after the siblings were already pending),
        // so insertion-order iteration would otherwise hit pendingB first.
        assertEquals(
            "confirmed-a",
            optimisticMessageIdForProjection(
                listOf(pendingB, pendingC, bridgeA),
                projection,
            ),
        )
    }

    @Test
    fun queuedMessagesKeepTheirOrderWhenIdsChangeDuringConfirmation() {
        val first = timelineMessage("first-temp", MessageStatus.Pending, plaintext = "A", timelineOrder = 1uL)
        val second = timelineMessage("second-temp", MessageStatus.Pending, plaintext = "B", timelineOrder = 2uL)
        val third = timelineMessage("third-temp", MessageStatus.Pending, plaintext = "C", timelineOrder = 3uL)
        val confirmedFirst = timelineMessage("zz-confirmed", MessageStatus.Sent, plaintext = "A", timelineOrder = 1uL)

        assertEquals(
            listOf("A", "B", "C"),
            listOf(second, third, confirmedFirst)
                .sortedWith(::compareTimelineMessages)
                .map { it.record.plaintext },
        )
    }

    @Test
    fun failedOptimisticMatchesCommittedUnpublishedProjectionByShapeNotTempId() {
        val failedOptimistic = message("uuid-temp", plaintext = "retry me")
        val projected =
            timelineRecord(
                messageIdHex = "engine-id",
                plaintext = "retry me",
                sourceMessageIdHex = null,
            )
        val match =
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            )
        assertEquals(projected, match)
    }

    @Test
    fun publishedProjectionIsNotMatchedForConvergenceRetry() {
        val failedOptimistic = message("uuid-temp", plaintext = "retry me")
        val projected =
            timelineRecord(
                messageIdHex = "engine-id",
                plaintext = "retry me",
                sourceMessageIdHex = "published-event-id",
            )
        assertNull(
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            ),
        )
    }

    @Test
    fun sameBodyDifferentTimestampIsNotMatchedForConvergenceRetry() {
        val failedOptimistic = message("uuid-temp", plaintext = "retry me", recordedAt = 1uL)
        val projected =
            timelineRecord(
                messageIdHex = "engine-id",
                plaintext = "retry me",
                sourceMessageIdHex = null,
                recordedAt = 10uL,
            )
        assertNull(
            committedButUnpublishedProjectionForOptimistic(
                mapOf(projected.messageIdHex to projected),
                failedOptimistic,
                "alice",
            ),
        )
    }

    @Test
    fun failedTextSendRetainsOptimisticBubbleForRetryAndCopy() {
        val optimistic = message("temp-id", plaintext = "copy me later")
        val optimisticMessages = linkedMapOf<String, TimelineMessage>()
        val messageById = linkedMapOf<String, AppMessageRecordFfi>()

        retainFailedOptimisticTextSend(
            optimisticMessages = optimisticMessages,
            messageById = messageById,
            key = "msg:temp-id",
            optimistic = optimistic,
            timelineOrder = 42uL,
        )

        val failed = optimisticMessages["msg:temp-id"]
        assertEquals(MessageStatus.Failed, failed?.status)
        assertEquals("copy me later", failed?.record?.plaintext)
        assertEquals(42uL, failed?.timelineOrder)
        assertEquals(optimistic, messageById["temp-id"])
    }

    @Test
    fun pruneMessageByIdDropsScrolledAwayRecordsButKeepsWindowAndOptimistic() {
        // Regression for #373: the live Upsert/Projection path adds a full
        // decrypted record per message and never trims, so messageById grows
        // unbounded for an actively-watched conversation. Pruning must collapse
        // it to the loaded window plus in-flight optimistic sends.
        val messageById = linkedMapOf<String, AppMessageRecordFfi>()
        repeat(100) { i -> messageById["m$i"] = message("m$i") }
        messageById["temp-pending"] = message("temp-pending")

        // Only the latest two records are in the loaded window; one optimistic
        // send is still in flight under its temp id.
        pruneMessageByIdToWindow(
            messageById = messageById,
            windowIds = setOf("m98", "m99"),
            optimisticMessages = listOf(timelineMessage("temp-pending", MessageStatus.Pending)),
        )

        assertEquals(setOf("m98", "m99", "temp-pending"), messageById.keys)
    }

    @Test
    fun mentionProjectionMatchesOptimisticDespiteEngineAddedPTag() {
        // #619: the engine derives a NIP-27 ["p", hex] tag from an @npub1… mention
        // on send, so the projected copy carries a p-tag the typed-text optimistic
        // lacks. Reconcile must still pair them (ignoring p-tags) or the sender sees
        // a transient double bubble until the confirmed id lands.
        val pending = timelineMessage("temp-id", MessageStatus.Pending, plaintext = "hi @npub1abc")
        val projected =
            AppMessageRecordFfi(
                messageIdHex = "confirmed",
                direction = "sent",
                groupIdHex = "group",
                sender = "alice",
                plaintext = "hi @npub1abc",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                tags = listOf(dev.ipf.marmotkit.MessageTagFfi(listOf("p", "deadbeef"))),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
        assertEquals("temp-id", optimisticMessageIdForProjection(listOf(pending), projected))
    }

    private fun mediaPending(
        id: String,
        filename: String,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "sent",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = "📎 $filename",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags =
                        listOf(
                            dev.ipf.marmotkit.MessageTagFfi(
                                listOf("_media_pending", filename, "application/pdf"),
                            ),
                        ),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Pending,
            timelineOrder = 1uL,
        )

    private fun mediaSent(
        id: String,
        filename: String,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "sent",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = "",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags =
                        listOf(
                            dev.ipf.marmotkit.MessageTagFfi(
                                listOf("imeta", "url https://example/$filename", "m application/pdf"),
                            ),
                        ),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Sent,
            timelineOrder = 1uL,
        )

    private fun mediaProjection(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags =
                listOf(
                    dev.ipf.marmotkit.MessageTagFfi(
                        listOf("imeta", "url https://example/a.pdf", "m application/pdf"),
                    ),
                ),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun timelineRecord(
        messageIdHex: String,
        plaintext: String,
        sourceMessageIdHex: String? = null,
        recordedAt: ULong = 1uL,
        invalidationStatus: String? = null,
        sender: String = "alice",
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = messageIdHex,
            sourceMessageIdHex = sourceMessageIdHex,
            direction = "sent",
            groupIdHex = "group",
            sender = sender,
            plaintext = plaintext,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            timelineAt = recordedAt,
            receivedAt = recordedAt,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = invalidationStatus,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
        plaintext: String = "hello",
        timelineOrder: ULong = 0uL,
        recordedAt: ULong = 1uL,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id, plaintext, recordedAt),
            status = status,
            timelineOrder = timelineOrder,
        )

    private fun message(
        id: String,
        plaintext: String = "hello",
        recordedAt: ULong = 1uL,
        direction: String = "sent",
        sender: String = "alice",
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = direction,
            groupIdHex = "group",
            sender = sender,
            plaintext = plaintext,
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
        )
}
