package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionOptimisticMessageHandoffTest {
    @Test
    fun explicitNullProjectionClearsRememberedHintAfterRetentionIsDisabled() {
        assertEquals(
            null,
            retentionHintForProjection(
                projectedRetentionSeconds = null,
                currentGroupRetentionSeconds = 0uL,
                optimisticSnapshot = null,
                rememberedSnapshot = 30uL,
            ),
        )
    }

    @Test
    fun activeRetentionKeepsPendingProjectionHintAcrossRefresh() {
        assertEquals(
            30uL,
            retentionHintForProjection(
                projectedRetentionSeconds = null,
                currentGroupRetentionSeconds = 30uL,
                optimisticSnapshot = null,
                rememberedSnapshot = 30uL,
            ),
        )
    }

    @Test
    fun optimisticHandoffKeepsHintUntilGroupStateRefreshSettles() {
        assertEquals(
            30uL,
            retentionHintForProjection(
                projectedRetentionSeconds = null,
                currentGroupRetentionSeconds = 0uL,
                optimisticSnapshot = 30uL,
                rememberedSnapshot = 30uL,
            ),
        )
    }

    @Test
    fun authoritativeRetentionProjectionRetiresLocalHint() {
        assertEquals(
            null,
            retentionHintForProjection(
                projectedRetentionSeconds = 30uL,
                currentGroupRetentionSeconds = 30uL,
                optimisticSnapshot = 30uL,
                rememberedSnapshot = 30uL,
            ),
        )
    }

    @Test
    fun confirmedIdTransitionPreservesTheSendTimeRetentionHint() {
        val tempId = "temp-retained"
        val key = "msg:$tempId"
        val record = message(tempId)
        val optimisticMessages =
            linkedMapOf(
                key to timelineMessage(tempId, MessageStatus.Pending, retentionAtSendSeconds = 30uL),
            )

        reconcileSuccessfulTextSend(
            summaryMessageIds = listOf("confirmed-retained"),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            optimisticKey = key,
            tempId = tempId,
            optimisticRecord = record,
            optimisticMessages = optimisticMessages,
            messageById = linkedMapOf(tempId to record),
            projectedMessageIds = emptySet(),
            timelineOrder = 5uL,
        )

        assertEquals(30uL, optimisticMessages["msg:confirmed-retained"]?.retentionAtSendSeconds)
    }

    @Test
    fun failedSendPreservesTheSendTimeRetentionHint() {
        val tempId = "temp-retained"
        val key = "msg:$tempId"
        val record = message(tempId)
        val optimisticMessages =
            linkedMapOf(
                key to timelineMessage(tempId, MessageStatus.Pending, retentionAtSendSeconds = 30uL),
            )

        retainFailedOptimisticTextSend(
            optimisticMessages = optimisticMessages,
            messageById = linkedMapOf(),
            key = key,
            optimistic = record,
            timelineOrder = 5uL,
        )

        assertEquals(30uL, optimisticMessages[key]?.retentionAtSendSeconds)
    }

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
        retentionAtSendSeconds: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id),
            status = status,
            retentionAtSendSeconds = retentionAtSendSeconds,
        )

    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "hello",
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
        )
}
