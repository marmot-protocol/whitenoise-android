package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimisticMessageReconciliationTest {
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
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = 9uL,
                    tags =
                        listOf(
                            dev.ipf.marmotkit.MessageTagFfi(
                                listOf("_media_pending", filename, "application/pdf"),
                            ),
                        ),
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
                    contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                    kind = 9uL,
                    tags =
                        listOf(
                            dev.ipf.marmotkit.MessageTagFfi(
                                listOf("imeta", "url https://example/$filename", "m application/pdf"),
                            ),
                        ),
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
            contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
            kind = 9uL,
            tags =
                listOf(
                    dev.ipf.marmotkit.MessageTagFfi(
                        listOf("imeta", "url https://example/a.pdf", "m application/pdf"),
                    ),
                ),
            recordedAt = 1uL,
            receivedAt = 1uL,
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
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = direction,
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
            kind = 9uL,
            tags = emptyList(),
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
}
