package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.text.AnnotatedString
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAttachmentReaderPolicyTest {
    @Test
    fun plainTextLoadsWithoutInvokingMarkdownParser() =
        runTest {
            var parserCalled = false

            val result =
                loadTextAttachmentPreview(
                    candidate = plainCandidate(),
                    bytes = "hello".toByteArray(),
                    parseMarkdown = {
                        parserCalled = true
                        document(it)
                    },
                )

            assertFalse(parserCalled)
            assertEquals("hello", (result as TextAttachmentReaderState.Ready).preview.text)
        }

    @Test
    fun markdownLoadsTheParsedDocumentAndPreservesTruncation() =
        runTest {
            val parsed = document("Rendered", truncated = true)

            val result =
                loadTextAttachmentPreview(
                    candidate = markdownCandidate(),
                    bytes = "# Rendered".toByteArray(),
                    parseMarkdown = { parsed },
                ) as TextAttachmentReaderState.Ready

            assertSame(parsed, result.preview.markdownDocument)
            assertTrue(result.preview.isTruncated)
        }

    @Test
    fun unsafePayloadsNeverReachTheMarkdownParser() =
        runTest {
            var parserCalled = false

            val result =
                loadTextAttachmentPreview(
                    candidate = markdownCandidate(),
                    bytes = "safe\u0000hidden".toByteArray(),
                    parseMarkdown = {
                        parserCalled = true
                        document(it)
                    },
                )

            assertFalse(parserCalled)
            assertEquals(
                TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.Binary),
                result,
            )
        }

    @Test
    fun copyPrefersTheActiveSelectionAndOtherwiseUsesTheFullSource() {
        assertEquals(
            "selected\nsecond",
            textAttachmentCopyText(
                selected = listOf(AnnotatedString("selected"), AnnotatedString("second")),
                fullText = "full source",
            ),
        )
        assertEquals("full source", textAttachmentCopyText(emptyList(), "full source"))
    }

    @Test
    fun markdownSpeechUsesRenderedLabelsWithoutPublishingTheUrl() {
        val preview =
            TextAttachmentPreview(
                candidate = markdownCandidate(),
                text = "[documentation](https://example.com/private)",
                markdownDocument =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks =
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(
                                        MarkdownInlineFfi.Link(
                                            dest = "https://example.com/private",
                                            title = null,
                                            children = listOf(MarkdownInlineFfi.Text("documentation")),
                                            classification = MarkdownLinkDestinationKindFfi.WEB,
                                        ),
                                    ),
                                ),
                            ),
                        blankLinesBefore = ByteArray(0),
                    ),
            )

        val entry = textAttachmentTtsEntry(preview, "alice", "Alice", "message", 2)

        assertEquals("documentation.", entry.text)
        assertFalse(entry.text.contains("example.com"))
        assertEquals("attachment:message:2", entry.messageIdHex)
        assertEquals("alice", entry.senderKey)
        assertEquals("Alice · notes.md", entry.senderDisplayName)
    }

    @Test
    fun emptyMarkdownAstFallsBackToBoundedSourceProjection() {
        val preview =
            TextAttachmentPreview(
                candidate = markdownCandidate(),
                text = "fallback source",
                markdownDocument =
                    MarkdownDocumentFfi(
                        blocks = emptyList(),
                        truncated = false,
                        blankLinesBefore = ByteArray(0),
                    ),
            )

        assertEquals("fallback source.", textAttachmentSpeakableText(preview))
    }

    @Test
    fun oversizedPayloadReturnsBeforeParsing() =
        runTest {
            var parserCalled = false

            val result =
                loadTextAttachmentPreview(
                    candidate = markdownCandidate(),
                    bytes = ByteArray(TEXT_ATTACHMENT_PREVIEW_MAX_BYTES + 1),
                    parseMarkdown = {
                        parserCalled = true
                        document(it)
                    },
                )

            assertFalse(parserCalled)
            assertEquals(
                TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge),
                result,
            )
        }

    @Test
    fun cancellationFromMarkdownParsingPropagates() =
        runTest {
            var cancelled = false
            try {
                loadTextAttachmentPreview(
                    candidate = markdownCandidate(),
                    bytes = "# title".toByteArray(),
                    parseMarkdown = { throw kotlinx.coroutines.CancellationException("closed") },
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    @Test
    fun plainTextSpeechIsBounded() {
        val preview =
            TextAttachmentPreview(
                candidate = plainCandidate(),
                text = "word ".repeat(20_000),
            )

        val spoken = textAttachmentSpeakableText(preview)

        assertTrue(spoken.length <= 32_000)
        assertFalse(spoken.lastOrNull()?.isHighSurrogate() == true)
    }

    private fun plainCandidate() = TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText)

    private fun markdownCandidate(): TextAttachmentCandidate {
        val format = TextAttachmentFormat.Markdown
        return TextAttachmentCandidate("notes.md", "text/markdown", format)
    }

    private fun document(
        text: String,
        truncated: Boolean = false,
    ) = MarkdownDocumentFfi(
        truncated = truncated,
        blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
        blankLinesBefore = ByteArray(0),
    )
}
