package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationEmojiLookupsTest {
    /** A media row for [messageIdHex] with a single [mediaType] attachment at [index] and [epoch]. */
    private fun media(
        messageIdHex: String,
        url: String,
        mediaType: String = "image/png",
        index: UInt = 0u,
        epoch: ULong = 3uL,
    ) = MediaRecordFfi(
        messageIdHex = messageIdHex,
        attachmentIndex = index,
        direction = "received",
        groupIdHex = "group",
        sender = "peer",
        reference =
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi("blossom", url)),
                ciphertextSha256 = "c-$url",
                plaintextSha256 = "p-$url",
                nonceHex = "",
                fileName = "emoji.png",
                mediaType = mediaType,
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = epoch,
                dim = null,
                thumbhash = null,
            ),
        caption = null,
        recordedAt = 0uL,
        receivedAt = 0uL,
    )

    /** A chip resolves to the image of exactly the reaction event MDK named for it. */
    @Test
    fun reactionResolvesToItsNamedEventImage() {
        val found =
            reactionEmojiAttachmentsFrom(
                listOf(media("other", "https://a/other"), media("R1", "https://a/party", index = 2u)),
                mapOf(":party:" to "r1"),
            ).getValue(":party:")
        assertEquals("r1", found.first)
        assertEquals(2, found.second.index)
        assertEquals(
            "https://a/party",
            found.second.value.locators
                .single()
                .value,
        )
    }

    /** A reaction event with no accepted image yields nothing, so the chip stays literal text. */
    @Test
    fun missingArtworkStaysLiteral() {
        val media = listOf(media("r1", "https://a/doc", mediaType = "application/pdf"))
        val none = emptyMap<String, EmojiAttachment>()
        assertEquals(none, reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1")))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "gone")))
        assertEquals(none, reactionEmojiAttachmentsFrom(emptyList(), emptyMap()))
    }

    /** Identical shortcodes on different messages resolve through their own events and never swap art. */
    @Test
    fun repeatedShortcodesKeepTheirOwnArtwork() {
        val media = listOf(media("r1", "https://a/first"), media("r2", "https://a/second"))
        val first = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1")).getValue(":party:")
        val second = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r2")).getValue(":party:")
        assertEquals(
            "https://a/first",
            first.second.value.locators
                .single()
                .value,
        )
        assertEquals(
            "https://a/second",
            second.second.value.locators
                .single()
                .value,
        )
    }

    /** A removed reaction leaves no chip to look up, and the lookup is empty for it. */
    @Test
    fun removedReactionHasNoArtwork() {
        val media = listOf(media("r1", "https://a/party"))
        assertNull(reactionEmojiAttachmentsFrom(media, emptyMap())[":party:"])
    }

    /** Rebuilding after a restart gives the same result, and the stored source epoch survives for decryption. */
    @Test
    fun lookupIsStableAcrossRestartAndKeepsTheSourceEpoch() {
        val media = listOf(media("r1", "https://a/party", epoch = 2uL))
        val before = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"))
        val after = reactionEmojiAttachmentsFrom(media.toList(), mapOf(":party:" to "r1"))
        assertEquals(before, after)
        assertEquals(
            2uL,
            after
                .getValue(":party:")
                .second.value.sourceEpoch,
        )
    }
}
