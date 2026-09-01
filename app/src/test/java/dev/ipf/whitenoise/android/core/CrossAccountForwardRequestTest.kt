package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The accepted forwarding snapshot is an independent fresh message: a
 * forwarded reply keeps only its selected text, and no source reply tag,
 * sender identity, or hidden attribution survives into the payload that
 * crosses the account boundary.
 */
class CrossAccountForwardRequestTest {
    /** A reply forwards as body-only text with no quote reference. */
    @Test
    fun forwardedReplyBecomesAnIndependentTextPayloadWithoutTheSourceReplyReference() {
        val quotedMessageId = "fe".repeat(32)
        val reply =
            message(
                id = "reply-1",
                sender = "carol-sender-identity",
                plaintext = "just the reply body",
                tags = listOf(MessageProjector.quoteTag(quotedMessageId)),
            )

        val eligibility =
            MessageProjector.forwardEligibility(
                message = reply,
                mediaReferences = emptyList(),
                nowSeconds = 100uL,
            )

        val payload = (eligibility as ForwardEligibility.Eligible).payload
        assertEquals(
            ForwardMessagePayload.Text(
                sourceGroupIdHex = "group",
                sourceMessageIdHex = "reply-1",
                text = "just the reply body",
            ),
            payload,
        )
    }

    /** An edited reply forwards its accepted edited text verbatim. */
    @Test
    fun editedReplyForwardsItsAcceptedEditedTextVerbatim() {
        val reply =
            message(
                id = "reply-2",
                plaintext = "original",
                tags = listOf(MessageProjector.quoteTag("ab".repeat(32))),
            )

        val eligibility =
            MessageProjector.forwardEligibility(
                message = reply,
                mediaReferences = emptyList(),
                editedText = "edited body",
                nowSeconds = 100uL,
            )

        val payload = (eligibility as ForwardEligibility.Eligible).payload as ForwardMessagePayload.Text
        assertEquals("edited body", payload.text)
    }

    /** Builds one chat message record with the given tags. */
    private fun message(
        id: String,
        sender: String = "alice",
        plaintext: String,
        tags: List<MessageTagFfi>,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = "received",
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )
}
