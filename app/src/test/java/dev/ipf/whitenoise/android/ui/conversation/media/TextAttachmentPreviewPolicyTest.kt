package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAttachmentPreviewPolicyTest {
    @Test
    fun classifierNormalizesMimeAndUsesOnlyTheFinalSanitizedExtension() {
        assertEquals(
            TextAttachmentFormat.Markdown,
            textAttachmentCandidate(" TEXT/MARKDOWN ; charset=UTF-8 ", "../notes.bin")?.format,
        )
        assertEquals(
            TextAttachmentFormat.Markdown,
            textAttachmentCandidate("application/octet-stream", "../notes.MARKDOWN")?.format,
        )
        assertNull(textAttachmentCandidate("application/octet-stream", "notes.md.exe"))
        assertEquals(
            "report.txt",
            textAttachmentCandidate("application/octet-stream", "report.\u202Etxt")?.displayName,
        )
        assertEquals(
            "attachment",
            textAttachmentCandidate("text/plain", "\u202E\u2066")?.displayName,
        )
        assertNull(textAttachmentCandidate("text/plain\napplication/json", "payload.bin"))
        assertEquals(
            "text/plain",
            textAttachmentCandidate("invalid mime", "payload.txt")?.normalizedMime,
        )
    }

    @Test
    fun classifierAllowsBoundedTextFamiliesButLeavesUnknownFilesExternal() {
        listOf(
            "text/plain" to "notes.bin",
            "text/html" to "page.html",
            "application/json" to "data.bin",
            "application/problem+json" to "problem.bin",
            "application/atom+xml" to "feed.bin",
            "application/octet-stream" to "notes.YML",
            "application/pdf" to "misleading.txt",
        ).forEach { (mime, name) ->
            assertEquals(TextAttachmentFormat.PlainText, textAttachmentCandidate(mime, name)?.format)
        }
        assertNull(textAttachmentCandidate("application/pdf", "report.pdf"))
        assertNull(textAttachmentCandidate("problem+json", "problem.bin"))
    }

    @Test
    fun exactByteBoundaryIsDecodedBeforeAnyOversizedStringCanBeBuilt() {
        val exact = ByteArray(TEXT_ATTACHMENT_PREVIEW_MAX_BYTES) { 'a'.code.toByte() }
        val oversized = exact + 'b'.code.toByte()

        assertTrue(decodeTextAttachment(exact) is TextAttachmentDecodeResult.Success)
        assertEquals(TextAttachmentDecodeResult.TooLarge, decodeTextAttachment(oversized))
    }

    @Test
    fun strictUtf8AndDeclaredUtf16BomsDecodeWithoutLeakingTheBom() {
        assertEquals(
            TextAttachmentDecodeResult.Success("hello", TextAttachmentEncoding.Utf8),
            decodeTextAttachment(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()),
        )
        assertEquals(
            TextAttachmentDecodeResult.Success("hello", TextAttachmentEncoding.Utf16Le),
            decodeTextAttachment(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            TextAttachmentDecodeResult.Success("hello", TextAttachmentEncoding.Utf16Be),
            decodeTextAttachment(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hello".toByteArray(Charsets.UTF_16BE)),
        )
    }

    @Test
    fun malformedUndeclaredAndBinaryPayloadsFailClosed() {
        assertEquals(
            TextAttachmentDecodeResult.InvalidEncoding,
            decodeTextAttachment(byteArrayOf(0xC3.toByte(), 0x28)),
        )
        assertEquals(
            TextAttachmentDecodeResult.InvalidEncoding,
            decodeTextAttachment(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61)),
        )
        assertEquals(
            TextAttachmentDecodeResult.Binary,
            decodeTextAttachment("hello".toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            TextAttachmentDecodeResult.Binary,
            decodeTextAttachment("safe\u0000hidden".toByteArray()),
        )
        assertEquals(
            TextAttachmentDecodeResult.Binary,
            decodeTextAttachment("safe\u0001hidden".toByteArray()),
        )
    }

    @Test
    fun emptyAndHtmlTextRemainInertPlainText() {
        assertEquals(
            TextAttachmentDecodeResult.Success("", TextAttachmentEncoding.Utf8),
            decodeTextAttachment(byteArrayOf()),
        )
        val html = "<script>alert('never execute')</script>"
        assertEquals(
            TextAttachmentDecodeResult.Success(html, TextAttachmentEncoding.Utf8),
            decodeTextAttachment(html.toByteArray()),
        )
    }
}
