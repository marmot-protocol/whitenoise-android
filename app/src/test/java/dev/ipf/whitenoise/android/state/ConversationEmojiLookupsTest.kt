package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
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

    /** The event's own `emoji` rows, keyed the way the lookup reads them. */
    private fun tags(
        eventId: String,
        vararg rows: List<String>,
    ) = mapOf(eventId to rows.map { MessageTagFfi(it) })

    /** A chip resolves to the attachment its event's emoji tag names, among several images. */
    @Test
    fun reactionResolvesToTheTaggedAttachment() {
        val media =
            listOf(
                media("other", "https://a/other"),
                media("r1", "https://a/decoy", index = 0u),
                media("r1", "https://a/party", index = 2u),
            )
        val found =
            reactionEmojiAttachmentsFrom(
                media,
                mapOf(":party:" to "r1"),
                tags("r1", listOf("emoji", "party", "https://a/party")),
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

    /** Without an emoji tag, or with one naming no attachment, the chip stays literal text. */
    @Test
    fun missingOrUnmatchedTagStaysLiteral() {
        val media = listOf(media("r1", "https://a/party"))
        val events = mapOf(":party:" to "r1")
        val none = emptyMap<String, EmojiAttachment>()
        assertEquals(none, reactionEmojiAttachmentsFrom(media, events, tags("r1")))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, events, emptyMap()))
        val otherUrl = tags("r1", listOf("emoji", "party", "https://a/x"))
        val otherCode = tags("r1", listOf("emoji", "wave", "https://a/party"))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, events, otherUrl))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, events, otherCode))
    }

    /** A reaction event with no accepted image yields nothing, so the chip stays literal text. */
    @Test
    fun missingArtworkStaysLiteral() {
        val media = listOf(media("r1", "https://a/doc", mediaType = "application/pdf"))
        val none = emptyMap<String, EmojiAttachment>()
        val row = tags("r1", listOf("emoji", "party", "https://a/doc"))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"), row))
        assertEquals(none, reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "gone"), row))
        assertEquals(none, reactionEmojiAttachmentsFrom(emptyList(), emptyMap(), emptyMap()))
    }

    /** The first well-formed tag for a shortcode wins, so a later redefinition cannot swap the artwork. */
    @Test
    fun firstTagForAShortcodeWins() {
        val media = listOf(media("r1", "https://a/first", index = 0u), media("r1", "https://a/second", index = 1u))
        val row =
            tags(
                "r1",
                listOf("emoji", "party", "https://a/first"),
                listOf("emoji", "party", "https://a/second"),
            )
        val found = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"), row).getValue(":party:")
        assertEquals(0, found.second.index)
    }

    /** Identical shortcodes on different messages resolve through their own events and never swap art. */
    @Test
    fun repeatedShortcodesKeepTheirOwnArtwork() {
        val media = listOf(media("r1", "https://a/first"), media("r2", "https://a/second"))
        val row =
            tags("r1", listOf("emoji", "party", "https://a/first")) +
                tags("r2", listOf("emoji", "party", "https://a/second"))
        val first = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"), row).getValue(":party:")
        val second = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r2"), row).getValue(":party:")
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
        assertNull(reactionEmojiAttachmentsFrom(media, emptyMap(), null)[":party:"])
    }

    /** Without tags the lookup only reports candidates, which gates the second native read. */
    @Test
    fun candidateModeReportsAnyImageOnTheEvent() {
        val media = listOf(media("r1", "https://a/party"))
        assertEquals(setOf(":party:"), reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"), null).keys)
        assertEquals(emptySet<String>(), reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r9"), null).keys)
    }

    /** Rebuilding after a restart gives the same result, and the stored source epoch survives for decryption. */
    @Test
    fun lookupIsStableAcrossRestartAndKeepsTheSourceEpoch() {
        val media = listOf(media("r1", "https://a/party", epoch = 2uL))
        val row = tags("r1", listOf("emoji", "party", "https://a/party"))
        val before = reactionEmojiAttachmentsFrom(media, mapOf(":party:" to "r1"), row)
        val after = reactionEmojiAttachmentsFrom(media.toList(), mapOf(":party:" to "r1"), row)
        assertEquals(before, after)
        assertEquals(
            2uL,
            after
                .getValue(":party:")
                .second.value.sourceEpoch,
        )
    }
}
