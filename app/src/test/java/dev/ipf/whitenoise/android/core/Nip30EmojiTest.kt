package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip30EmojiTest {
    private fun reference(
        mediaType: String,
        vararg urls: String,
    ) = MediaAttachmentReferenceFfi(
        locators = urls.map { MediaLocatorFfi("blossom", it) },
        ciphertextSha256 = "",
        plaintextSha256 = "",
        nonceHex = "",
        fileName = "emoji",
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

    private fun tag(vararg values: String) = MessageTagFfi(values.toList())

    @Test
    fun emojiTagClaimsTheImageWhoseAnyLocatorMatches() {
        // Protocol index 2: a rejected sibling at 1 keeps its slot.
        val attachments =
            listOf(
                IndexedValue(0, reference("image/png", "https://a/photo")),
                IndexedValue(2, reference("image/png", "https://a/mirror", "https://a/party")),
            )
        val tags = listOf(tag("imeta", "url https://a/party"), tag("emoji", "party", "https://a/party"))
        assertEquals(mapOf(2 to ":party:"), Nip30Emoji.attachmentShortcodes(tags, attachments))
    }

    @Test
    fun malformedTagsAndNonImagesDefineNothing() {
        val attachments =
            listOf(
                IndexedValue(0, reference("image/png", "https://a/x")),
                IndexedValue(1, reference("video/mp4", "https://a/v")),
            )
        val tags =
            listOf(
                tag("emoji", "short", ""),
                tag("emoji", "x"),
                tag("emoji", "has space", "https://a/x"),
                tag("emoji", "a".repeat(65), "https://a/x"),
                tag("emoji", "", "https://a/x"),
                tag("emojis", "x", "https://a/x"),
                tag("emoji", "clip", "https://a/v"),
                tag("emoji", "elsewhere", "https://a/other"),
            )
        assertEquals(emptyMap<Int, String>(), Nip30Emoji.attachmentShortcodes(tags, attachments))
    }

    @Test
    fun firstTagWinsPerAttachmentAndPerCode() {
        val attachments =
            listOf(
                IndexedValue(0, reference("image/gif", "https://a/one")),
                IndexedValue(1, reference("image/webp", "https://a/two")),
            )
        val tags =
            listOf(
                tag("emoji", "one", "https://a/one"),
                tag("emoji", "again", "https://a/one"),
                tag("emoji", "one", "https://a/two"),
                tag("emoji", "Two-2", "https://a/two"),
            )
        assertEquals(mapOf(0 to ":one:", 1 to ":Two-2:"), Nip30Emoji.attachmentShortcodes(tags, attachments))
    }

    @Test
    fun onlyAnEmojiTagMarksFullEventTags() {
        assertTrue(Nip30Emoji.hasEmojiTags(listOf(tag("imeta"), tag("emoji", "a", "u"))))
        assertFalse(Nip30Emoji.hasEmojiTags(listOf(tag("imeta", "url u"))))
        assertFalse(Nip30Emoji.hasEmojiTags(listOf(MessageTagFfi(emptyList()))))
    }
}
