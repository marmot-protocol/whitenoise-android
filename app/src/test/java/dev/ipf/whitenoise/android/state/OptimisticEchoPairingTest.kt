package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pairing a projected relay echo with the pending bubble it confirms, before the
 * send call has returned the confirmed id. The optimistic copy carries only the
 * typed text and its reply tags; everything else on the echo is engine-derived.
 */
class OptimisticEchoPairingTest {
    /** Any tag the engine adds on publish, mention or not, must not keep the echo apart from its pending bubble. */
    @Test
    fun projectionMatchesOptimisticDespiteAnyEngineAddedTag() {
        val pending = timelineMessage("temp-id", MessageStatus.Pending, plaintext = "see you soon")
        val projected =
            message("confirmed", plaintext = "see you soon").copy(
                tags =
                    listOf(
                        MessageTagFfi(listOf("expiration", "1789000000")),
                        MessageTagFfi(listOf("provenance", "device-a")),
                    ),
            )
        assertEquals("temp-id", optimisticMessageIdForProjection(listOf(pending), projected))
    }

    /** The reply identity still has to agree: a reply to another message is not this pending bubble's echo. */
    @Test
    fun projectionWithADifferentReplyTargetIsNotMatched() {
        val pendingReply =
            timelineMessage("temp-id", MessageStatus.Pending, plaintext = "agreed").let { pending ->
                pending.copy(
                    record =
                        pending.record.copy(
                            tags = listOf(MessageProjector.eventTag("parent-a"), MessageProjector.quoteTag("parent-a")),
                        ),
                )
            }
        val projectedOtherReply =
            message("confirmed", plaintext = "agreed").copy(
                tags = listOf(MessageProjector.eventTag("parent-b"), MessageProjector.quoteTag("parent-b")),
            )
        val projectedSameReply =
            message("confirmed", plaintext = "agreed").copy(
                tags =
                    listOf(
                        MessageProjector.eventTag("parent-a"),
                        MessageProjector.quoteTag("parent-a"),
                        MessageTagFfi(listOf("p", "deadbeef")),
                    ),
            )
        assertNull(optimisticMessageIdForProjection(listOf(pendingReply), projectedOtherReply))
        assertEquals("temp-id", optimisticMessageIdForProjection(listOf(pendingReply), projectedSameReply))
    }

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
        plaintext: String,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id, plaintext),
            status = status,
            timelineOrder = 0uL,
        )

    private fun message(
        id: String,
        plaintext: String,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
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
            recordedAt = 1uL,
            receivedAt = 1uL,
        )
}
