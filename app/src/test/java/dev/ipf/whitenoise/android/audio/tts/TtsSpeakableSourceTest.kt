package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MessageTagFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TtsSpeakableSourceTest {
    @Test
    fun resolveTtsSpeakableSourceUsesStoredTokensForUneditedMarkdown() {
        val record = message(contentTokens = markdownDocument("Hello **world**"))

        val source = resolveTtsSpeakableSource(record, editedText = null)

        assertNotNull(source)
        assertEquals("Hello **world**", source!!.text)
        assertTrue(source.useStoredContentTokens)
    }

    @Test
    fun resolveTtsSpeakableSourceReparsesEditedMarkdown() {
        val record = message(contentTokens = markdownDocument("Original **value**"))

        val source = resolveTtsSpeakableSource(record, editedText = "Edited *value*")

        assertNotNull(source)
        assertEquals("Edited *value*", source!!.text)
        assertFalse(source.useStoredContentTokens)
    }

    @Test
    fun resolveTtsSpeakableDocumentParsesEditedSourceOnce() =
        runBlocking {
            val record = message(contentTokens = markdownDocument("Original **value**"))
            val editedDocument =
                markdownDocument(
                    "Edited ",
                    MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("value"))),
                )
            val source = resolveTtsSpeakableSource(record, editedText = "Edited *value*")!!
            var parseCalls = 0

            val document =
                resolveTtsSpeakableDocument(record, source) {
                    parseCalls += 1
                    editedDocument
                }

            assertEquals(1, parseCalls)
            assertEquals(editedDocument, document)
        }

    @Test
    fun resolveTtsSpeakableDocumentRethrowsCancellation() =
        runBlocking {
            val record = message(contentTokens = markdownDocument("Original **value**"))
            val source = resolveTtsSpeakableSource(record, editedText = "Edited *value*")!!

            try {
                resolveTtsSpeakableDocument(record, source) {
                    throw CancellationException("parse cancelled")
                }
                fail("Expected CancellationException")
            } catch (_: CancellationException) {
                // Expected.
            }
        }

    @Test
    fun resolveTtsSpeakableDocumentFallsBackToEmptyDocumentOnParseFailure() =
        runBlocking {
            val record = message(contentTokens = markdownDocument("Original **value**"))
            val source = resolveTtsSpeakableSource(record, editedText = "Edited *value*")!!

            val document =
                resolveTtsSpeakableDocument(record, source) {
                    throw IllegalStateException("parse failed")
                }

            assertTrue(document.blocks.isEmpty())
            assertFalse(document.truncated)
        }

    private fun markdownDocument(
        prefix: String,
        formatted: MarkdownInlineFfi,
    ) = MarkdownDocumentFfi(
        truncated = false,
        blankLinesBefore = byteArrayOf(),
        blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    inlines =
                        listOf(
                            MarkdownInlineFfi.Text(prefix),
                            formatted,
                        ),
                ),
            ),
    )

    private fun markdownDocument(plaintext: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines = listOf(MarkdownInlineFfi.Text(plaintext)),
                    ),
                ),
        )

    private fun message(contentTokens: MarkdownDocumentFfi) =
        AppMessageRecordFfi(
            messageIdHex = "message",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Hello **world**",
            contentTokens = contentTokens,
            kind = 9uL,
            tags = emptyList<MessageTagFfi>(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )
}
