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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsMessageProjectionTest {
    @Test
    fun markdownProjectionKeepsStableVisibleLeafMappings() =
        runBlocking {
            val link =
                MarkdownInlineFfi.Link(
                    dest = "https://hidden.example/private",
                    title = null,
                    children = listOf(MarkdownInlineFfi.Text("docs")),
                    classification = dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi.WEB,
                )
            val record =
                message(
                    plaintext = "Read **bold** [docs](https://hidden.example/private)",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks =
                                listOf(
                                    MarkdownBlockFfi.Paragraph(
                                        listOf(
                                            MarkdownInlineFfi.Text("Read "),
                                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                                            MarkdownInlineFfi.Text(" "),
                                            link,
                                        ),
                                    ),
                                ),
                            blankLinesBefore = byteArrayOf(),
                        ),
                )

            val entry =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { error("stored tokens should win") },
                )!!

            assertEquals("Read bold docs.", entry.text)
            assertEquals(
                listOf(
                    TtsSpokenTextSpan(TtsTextRange(0, 5), TtsVisibleTextSpan("b0/n0", 0, 5)),
                    TtsSpokenTextSpan(TtsTextRange(5, 9), TtsVisibleTextSpan("b0/n1/n0", 0, 4)),
                    TtsSpokenTextSpan(TtsTextRange(9, 10), TtsVisibleTextSpan("b0/n2", 0, 1)),
                    TtsSpokenTextSpan(TtsTextRange(10, 14), TtsVisibleTextSpan("b0/n3/n0", 0, 4)),
                ),
                entry.spokenTextSpans,
            )
            assertTrue(entry.projectionId.isNotEmpty())
            assertFalse(entry.text.contains("hidden.example"))
        }

    @Test
    fun legacyProjectionMapsTextAfterARemovedUrlToItsActualVisibleOccurrence() =
        runBlocking {
            val emptyDocument =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
            val entry =
                projectTtsSpeakableEntry(
                    message = message("before https://example.com/after after", contentTokens = emptyDocument),
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                )!!

            assertEquals("before after.", entry.text)
            assertEquals(
                listOf(
                    TtsSpokenTextSpan(TtsTextRange(0, 7), TtsVisibleTextSpan("plain", 0, 7)),
                    TtsSpokenTextSpan(TtsTextRange(7, 12), TtsVisibleTextSpan("plain", 33, 38)),
                ),
                entry.spokenTextSpans,
            )
        }

    @Test
    fun emojiFinalProjectionReachesEngineWithoutSyntheticSuffix() =
        runBlocking {
            val emptyDocument =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
            val entry =
                projectTtsSpeakableEntry(
                    message = message("Great job 😀", contentTokens = emptyDocument),
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                )!!
            val engine = FakeSessionEngine()
            val controller = TtsController(audioFocus = FakeSessionFocus(), maxChunkLength = 4_000)
            controller.attachEngine(engine)

            assertTrue(controller.speak(listOf(entry), Locale.US))
            val spokenText = engine.spoken.single().text
            assertEquals("Alice: Great job 😀", spokenText)
            assertFalse(spokenText.endsWith("."))
        }

    @Test
    fun legacyProjectionMapsSanitizedVisibleOffsetsAndLeavesSyntheticPunctuationUnmapped() =
        runBlocking {
            val emptyDocument =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
            val entry =
                projectTtsSpeakableEntry(
                    message = message("Hello😀 world", contentTokens = emptyDocument),
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                )!!

            assertEquals("Hello😀 world.", entry.text)
            assertEquals(
                listOf(
                    TtsSpokenTextSpan(TtsTextRange(0, 13), TtsVisibleTextSpan("plain", 0, 13)),
                ),
                entry.spokenTextSpans,
            )
        }

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
            val emptyDocument =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
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
                    message = message(plaintext = "https://example.com/private", contentTokens = emptyDocument),
                    editedText = null,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                ),
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
                        contentTokens =
                            MarkdownDocumentFfi(
                                truncated = false,
                                blocks = emptyList(),
                                blankLinesBefore = byteArrayOf(),
                            ),
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
        blankLinesBefore = byteArrayOf(),
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
