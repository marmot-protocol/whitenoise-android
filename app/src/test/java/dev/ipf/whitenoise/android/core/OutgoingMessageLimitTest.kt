package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutgoingMessageLimitTest {
    /** Text of exactly the limit is sent, one character more is not. */
    @Test
    fun theLimitItselfIsAllowed() {
        assertFalse(exceedsOutgoingMessageLimit("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS)))
        assertTrue(exceedsOutgoingMessageLimit("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1)))
    }

    /** An emoji with a skin tone spans four UTF-16 units but counts as one character. */
    @Test
    fun emojiSequencesCountOnce() {
        val emoji = "👍🏽"
        assertFalse(exceedsOutgoingMessageLimit(emoji.repeat(MAX_OUTGOING_MESSAGE_CHARACTERS)))
        assertTrue(exceedsOutgoingMessageLimit(emoji.repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1)))
    }

    /** A text message over the limit cannot be forwarded and says why, one at the limit still can. */
    @Test
    fun overLimitTextIsBlockedFromForwarding() {
        val atLimit = record("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS))
        val overLimit = record("a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1))

        assertTrue(forward(atLimit) is ForwardEligibility.Eligible)
        assertEquals(ForwardEligibility.Blocked(ForwardBlockedReason.TooLong), forward(overLimit))
    }

    /** An attachment whose caption is over the limit cannot be forwarded either. */
    @Test
    fun overLimitCaptionIsBlockedFromForwarding() {
        val captioned =
            record(
                plaintext = "a".repeat(MAX_OUTGOING_MESSAGE_CHARACTERS + 1),
                tags = listOf(MessageTagFfi(listOf("imeta", "photo"))),
            )

        assertEquals(
            ForwardEligibility.Blocked(ForwardBlockedReason.TooLong),
            forward(captioned, listOf(photoReference())),
        )
    }

    /** Asks for the forward eligibility of [message] carrying [references]. */
    private fun forward(
        message: AppMessageRecordFfi,
        references: List<MediaAttachmentReferenceFfi> = emptyList(),
    ) = MessageProjector.forwardEligibility(message = message, mediaReferences = references, nowSeconds = 100uL)

    /** A committed received chat message with [plaintext] and [tags]. */
    private fun record(
        plaintext: String,
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "message",
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
        sticker = null,
    )

    /** A downloadable photo reference, so only the caption can block the forward. */
    private fun photoReference() =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/blob")),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "photo.jpg",
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
}
