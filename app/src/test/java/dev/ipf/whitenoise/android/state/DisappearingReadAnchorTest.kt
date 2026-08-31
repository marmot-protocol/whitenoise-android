package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers read deferral without allowing group-policy changes to rewrite history. */
class DisappearingReadAnchorTest {
    /** Duplicate reconciliation ids keep the first projected ordering position. */
    @Test
    fun messageOrderPreservesFirstOccurrenceForOptimisticReconciliationDuplicates() {
        assertTrue(firstMessageOrder(listOf("before", "duplicate", "duplicate", "after"))["duplicate"] == 1)
    }

    /** Locally sent rows never wait for a received-message read watermark. */
    @Test
    fun ownSendNeverDefersSendTimeExpiry() {
        assertFalse(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "sent", id = "s1", timelineAt = 100uL),
                lastReadMessageId = null,
                lastReadTimelineAt = null,
                messageOrder = firstMessageOrder(listOf("s1")),
            ),
        )
    }

    /** Received rows defer when no durable read watermark exists. */
    @Test
    fun unreadReceivedDefersWhenNoReadWatermark() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r1", timelineAt = 100uL),
                lastReadMessageId = null,
                lastReadTimelineAt = null,
                messageOrder = firstMessageOrder(listOf("r1")),
            ),
        )
    }

    /** A received row after the last-read id remains deferred. */
    @Test
    fun receivedAfterReadMessageIdDefers() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r2", timelineAt = 200uL),
                lastReadMessageId = "r1",
                lastReadTimelineAt = 150uL,
                messageOrder = firstMessageOrder(listOf("r1", "r2")),
            ),
        )
        assertFalse(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r1", timelineAt = 100uL),
                lastReadMessageId = "r1",
                lastReadTimelineAt = 150uL,
                messageOrder = firstMessageOrder(listOf("r1", "r2")),
            ),
        )
    }

    /** A group-system read watermark remains an ordering anchor even though it never expires. */
    @Test
    fun groupSystemReadAnchorPartitionsOrdinaryRowsUsingTheCompleteOrder() {
        val messageOrder = firstMessageOrder(listOf("before", "retention-change", "after"))

        assertFalse(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "before", timelineAt = 100uL),
                lastReadMessageId = "retention-change",
                lastReadTimelineAt = null,
                messageOrder = messageOrder,
            ),
        )
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "after", timelineAt = 100uL),
                lastReadMessageId = "retention-change",
                lastReadTimelineAt = null,
                messageOrder = messageOrder,
            ),
        )
    }

    /** Timeline time provides the fallback ordering when no read id exists. */
    @Test
    fun receivedAfterLastReadTimelineAtDefers() {
        assertTrue(
            isDisappearingSendTimeExpiryDeferred(
                record = message(direction = "received", id = "r2", timelineAt = 200uL),
                lastReadMessageId = null,
                lastReadTimelineAt = 150uL,
                messageOrder = firstMessageOrder(listOf("r1", "r2")),
            ),
        )
    }

    /** Group-system history is excluded from local message expiry. */
    @Test
    fun groupSystemHistoryNeverParticipatesInLocalMessageExpiry() {
        val groupSystem =
            message(
                direction = "received",
                id = "retention-change",
                timelineAt = 100uL,
                kind = 1210uL,
            )
        val expiredAtSendTime =
            DisappearingMessageSweep.LocalExpiryRow(
                timelineAtSeconds = groupSystem.recordedAt,
                retentionAtSendSeconds = 60uL,
            )

        assertFalse(shouldApplyLocalDisappearingExpiry(groupSystem))
        assertFalse(
            isTimelineRecordLocallyExpired(
                nowMillis = 1_000_000L,
                record = groupSystem,
                row = expiredAtSendTime,
            ),
        )
    }

    /** Ordinary projected rows expire only when they own a pinned deadline. */
    @Test
    fun ordinaryProjectedMessageRequiresItsOwnPinnedExpiry() {
        val chatMessage = message(direction = "received", id = "chat-message", timelineAt = 100uL)

        assertFalse(
            isTimelineRecordLocallyExpired(
                nowMillis = 1_000_000L,
                record = chatMessage,
                row = DisappearingMessageSweep.LocalExpiryRow(timelineAtSeconds = chatMessage.recordedAt),
            ),
        )
        assertTrue(
            isTimelineRecordLocallyExpired(
                nowMillis = 1_000_000L,
                record = chatMessage,
                row =
                    DisappearingMessageSweep.LocalExpiryRow(
                        timelineAtSeconds = chatMessage.recordedAt,
                        retentionAtSendSeconds = 60uL,
                    ),
            ),
        )
    }

    /** Builds the minimal projected row used by read-anchor decisions. */
    private fun message(
        direction: String,
        id: String,
        timelineAt: ULong,
        kind: ULong = 9uL,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = direction,
            groupIdHex = "group",
            sender = "sender",
            plaintext = "hi",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = kind,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = timelineAt,
            receivedAt = timelineAt,
        )
}
