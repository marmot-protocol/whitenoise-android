package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AcceptedPendingMessageReconciliationTest {
    @Test
    fun acceptedPendingTextSendKeepsTheBubblePendingUntilProjection() {
        val tempId = "temp-accepted-pending"
        val canonicalMessageId = "a".repeat(64)
        val key = "msg:$tempId"
        val record = message(tempId)
        val optimisticMessages =
            linkedMapOf(
                key to TimelineMessage(id = key, record = record, status = MessageStatus.Pending),
            )
        val messageById = linkedMapOf(tempId to record)
        val acceptedPendingOptimisticIdsByMessageId = linkedMapOf<String, String>()

        val reconciliation =
            reconcileSuccessfulTextSend(
                summaryMessageIds = listOf(canonicalMessageId),
                acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                optimisticKey = key,
                tempId = tempId,
                optimisticRecord = record,
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                projectedMessageIds = emptySet(),
                timelineOrder = 13uL,
                acceptedPendingTextOptimisticIdsByMessageId = acceptedPendingOptimisticIdsByMessageId,
            )

        assertFalse(reconciliation.awaitingEcho)
        assertTrue(reconciliation.acceptedPending)
        assertTrue(reconciliation.awaitingProjection)
        assertFalse(reconciliation.insertedSent)
        assertEquals(MessageStatus.Pending, optimisticMessages[key]?.status)
        assertEquals(record, messageById[tempId])
        assertEquals(tempId, acceptedPendingOptimisticIdsByMessageId[canonicalMessageId])
    }

    @Test
    fun acceptedPendingMediaProjectionMatchesItsCanonicalIdWithOtherMediaQueued() {
        val firstMessageId = "a".repeat(64)
        val secondMessageId = "b".repeat(64)
        val acceptedPendingMessageIdsByOptimisticId =
            mapOf(
                "first-temp" to firstMessageId,
                "second-temp" to secondMessageId,
            )

        assertEquals(
            "second-temp",
            acceptedPendingMediaOptimisticIdForProjection(
                projectedMessageIdHex = secondMessageId,
                acceptedPendingMessageIdsByOptimisticId = acceptedPendingMessageIdsByOptimisticId,
            ),
        )
        assertEquals(
            "first-temp",
            acceptedPendingMediaOptimisticIdForProjection(
                projectedMessageIdHex = firstMessageId,
                acceptedPendingMessageIdsByOptimisticId = acceptedPendingMessageIdsByOptimisticId,
            ),
        )
        assertNull(
            acceptedPendingMediaOptimisticIdForProjection(
                projectedMessageIdHex = "c".repeat(64),
                acceptedPendingMessageIdsByOptimisticId = acceptedPendingMessageIdsByOptimisticId,
            ),
        )
    }

    @Test
    fun acceptedPendingTextProjectionMatchesItsCanonicalIdWithIdenticalTextsQueued() {
        val firstMessageId = "a".repeat(64)
        val secondMessageId = "b".repeat(64)
        val acceptedPendingOptimisticIdsByMessageId =
            mapOf(
                firstMessageId to "first-temp",
                secondMessageId to "second-temp",
            )

        assertEquals(
            "second-temp",
            acceptedPendingTextOptimisticIdForProjection(
                projectedMessageIdHex = secondMessageId,
                acceptedPendingOptimisticIdsByMessageId = acceptedPendingOptimisticIdsByMessageId,
            ),
        )
        assertEquals(
            "first-temp",
            acceptedPendingTextOptimisticIdForProjection(
                projectedMessageIdHex = firstMessageId,
                acceptedPendingOptimisticIdsByMessageId = acceptedPendingOptimisticIdsByMessageId,
            ),
        )
        assertNull(
            acceptedPendingTextOptimisticIdForProjection(
                projectedMessageIdHex = "c".repeat(64),
                acceptedPendingOptimisticIdsByMessageId = acceptedPendingOptimisticIdsByMessageId,
            ),
        )
    }

    @Test
    fun acceptedPendingMediaChecksDiscardBeforeRestoringItsCanonicalBridge() {
        val source = controllersSource().readText()
        val acceptedPendingBranch =
            source.indexOf("if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING)")
        val discardCheck = source.indexOf("if (discardedDuringRetry.remove(key))", acceptedPendingBranch)
        val bridgeStore = source.indexOf("retained.acceptedPending = true", acceptedPendingBranch)

        assertTrue("accepted-pending media branch must exist", acceptedPendingBranch >= 0)
        assertTrue(
            "discard must be checked before the canonical bridge is stored",
            discardCheck > acceptedPendingBranch,
        )
        assertTrue("the canonical bridge must be stored after the discard check", bridgeStore > discardCheck)
        val discardBlock = source.substring(discardCheck, bridgeStore)

        // The shared cleanup clears the optimistic bubble, both upload maps and,
        // for an in-flight discard, the chat-list preview.
        assertTrue(
            "discard must run the shared optimistic-send cleanup",
            "discardOptimisticSend(key, tempId, OptimisticSendDiscard.DISCARDED_IN_FLIGHT)" in discardBlock,
        )
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
        ).firstOrNull(File::exists) ?: error("Missing ConversationController.kt source file")

    /** Builds a sent message record from alice with the given id. */
    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "queued for publication",
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
            recordedAt = 1uL,
            receivedAt = 1uL,
            sticker = null,
        )
}
