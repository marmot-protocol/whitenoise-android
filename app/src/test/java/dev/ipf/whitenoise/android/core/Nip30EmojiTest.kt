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

    /** Sending finds each distinct `:code:` once, in the order typed, and ignores lone colons. */
    @Test
    fun shortcodesInTextAreDistinctAndOrdered() {
        assertEquals(
            listOf(":party:", ":Two-2:"),
            Nip30Emoji.shortcodesIn("hi :party: and :Two-2: then :party: at noon: ok"),
        )
        assertEquals(emptyList<String>(), Nip30Emoji.shortcodesIn("no codes: here, 10:30 :: ::"))
    }

    /** Codes inside inline code and fenced blocks are literal, so they never attach artwork. */
    @Test
    fun shortcodesInsideCodeAreIgnored() {
        assertEquals(listOf(":real:"), Nip30Emoji.shortcodesIn("`:span:` and :real: and ``:double:``"))
        assertEquals(listOf(":after:"), Nip30Emoji.shortcodesIn("```\n:fenced:\n```\n:after:"))
        assertEquals(emptyList<String>(), Nip30Emoji.shortcodesIn("~~~\n:tilde:\n~~~"))
    }

    /** Only the NIP-30 alphabet MDK accepts for a sent tag, up to 62 characters, can be sent. */
    @Test
    fun sendableShortcodesUseTheStrictAlphabet() {
        assertTrue(Nip30Emoji.isSendable(":party_1:"))
        assertTrue(Nip30Emoji.isSendable(":" + "a".repeat(62) + ":"))
        assertFalse(Nip30Emoji.isSendable(":" + "a".repeat(63) + ":"))
        assertFalse(Nip30Emoji.isSendable(":ship-it:"))
        assertFalse(Nip30Emoji.isSendable("::"))
    }

    /** The emitted row names the attachment by its first locator, with the shortcode unwrapped. */
    @Test
    fun tagNamesTheShortcodeWithoutColonsAndTheUrl() {
        assertEquals(listOf("emoji", "party", "https://a/party"), Nip30Emoji.tag(":party:", "https://a/party"))
    }
}
