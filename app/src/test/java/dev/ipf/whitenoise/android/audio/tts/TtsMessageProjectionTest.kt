package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MessageTagFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsMessageProjectionTest {
    @Test
    fun originalAndEditedMessagesUseTheirActiveMarkdownDocument() =
        runBlocking {
            val originalDocument =
                document(
                    "Original ",
                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("value"))),
                )
            val editedDocument =
                document(
                    "Edited ",
                    MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("value"))),
                )
            val record = message(plaintext = "Original **value**", contentTokens = originalDocument)
            var parseCalls = 0

            assertEquals(
                "Original value.",
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = {
                        parseCalls++
                        editedDocument
                    },
                )?.text,
            )
            assertEquals(0, parseCalls)

            assertEquals(
                "Edited value.",
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = "Edited *value*",
                    senderDisplayName = "Alice",
                    parseMarkdown = { source ->
                        assertEquals("Edited *value*", source)
                        parseCalls++
                        editedDocument
                    },
                )?.text,
            )
            assertEquals(1, parseCalls)
        }

    @Test
    fun legacyFallbackOmitsUrlsAndNonMessageKindsRemainFiltered() =
        runBlocking {
            val emptyDocument = MarkdownDocumentFfi(truncated = false, blocks = emptyList())
            val legacy = message(plaintext = "Details: https://example.com/private", contentTokens = emptyDocument)

            assertEquals(
                "Details.",
                projectTtsSpeakableEntry(
                    message = legacy,
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                )?.text,
            )
            assertNull(
                projectTtsSpeakableEntry(
                    message = message(plaintext = "👍", kind = 7uL, contentTokens = emptyDocument),
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                ),
            )
        }

    @Test(expected = CancellationException::class)
    fun markdownParsingCancellationStillCancelsTheCaller() {
        runBlocking {
            projectTtsSpeakableEntry(
                message =
                    message(
                        plaintext = "Edited value",
                        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    ),
                editedText = "Edited value",
                senderDisplayName = "Alice",
                parseMarkdown = { throw CancellationException("cancelled") },
            )
        }
    }

    private fun document(
        prefix: String,
        formatted: MarkdownInlineFfi,
    ) = MarkdownDocumentFfi(
        truncated = false,
        blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    listOf(
                        MarkdownInlineFfi.Text(prefix),
                        formatted,
                    ),
                ),
            ),
    )

    private fun message(
        plaintext: String,
        kind: ULong = 9uL,
        contentTokens: MarkdownDocumentFfi,
    ) = AppMessageRecordFfi(
        messageIdHex = "message",
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens = contentTokens,
        kind = kind,
        tags = emptyList<MessageTagFfi>(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )
}
