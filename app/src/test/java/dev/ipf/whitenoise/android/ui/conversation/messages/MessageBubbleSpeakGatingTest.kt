package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.MessageProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleSpeakGatingTest {
    @Test
    fun captionlessAttachmentWithDisplayFilenameDoesNotOfferSpeakAloud() {
        val pdf =
            message(
                plaintext = "",
                tags =
                    listOf(
                        MessageTagFfi(listOf("imeta", "m application/pdf", "filename report.pdf")),
                    ),
            )

        assertTrue(
            "regression guard: bubble display fallback is nonblank while copy/TTS text is absent",
            MessageProjector.displayBody(pdf).isNotBlank(),
        )
        assertFalse(
            messageBubbleCanSpeak(
                record = pdf,
                editedText = null,
                deleted = false,
                invalidated = false,
                ttsHasUsableEngine = true,
            ),
        )
    }

    @Test
    fun plainTextEditedTextAndCaptionsRemainSpeakableWhenEngineExists() {
        val text = message(plaintext = "hello")
        val captioned =
            message(
                plaintext = "caption",
                tags = listOf(MessageTagFfi(listOf("imeta", "m image/png"))),
            )

        assertTrue(
            messageBubbleCanSpeak(
                text,
                editedText = null,
                deleted = false,
                invalidated = false,
                ttsHasUsableEngine = true,
            ),
        )
        assertTrue(
            messageBubbleCanSpeak(
                text,
                editedText = "edited",
                deleted = false,
                invalidated = false,
                ttsHasUsableEngine = true,
            ),
        )
        assertTrue(
            messageBubbleCanSpeak(
                captioned,
                editedText = null,
                deleted = false,
                invalidated = false,
                ttsHasUsableEngine = true,
            ),
        )
    }

    @Test
    fun speakAloudRequiresUsableEngine() {
        val text = message(plaintext = "hello")

        assertFalse(
            messageBubbleCanSpeak(
                text,
                editedText = null,
                deleted = false,
                invalidated = false,
                ttsHasUsableEngine = false,
            ),
        )
    }

    @Test
    fun speakFromHereSkipsCaptionlessAttachmentsAndContinuesWithText() =
        runBlocking {
            val emptyDocument =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                )
            val captionlessPdf =
                message(
                    id = "pdf",
                    plaintext = "",
                    tags =
                        listOf(
                            MessageTagFfi(listOf("imeta", "m application/pdf", "filename report.pdf")),
                        ),
                )
            val text = message(id = "text", plaintext = "hello")

            val entries =
                ttsSpeakFromHereCandidates(
                    timeline =
                        listOf(
                            timelineMessage(captionlessPdf),
                            timelineMessage(text),
                        ),
                    selected = captionlessPdf,
                ).mapNotNull { record ->
                    projectTtsSpeakableEntry(
                        message = record,
                        editedText = null,
                        senderDisplayName = "Alice",
                        parseMarkdown = { emptyDocument },
                    )
                }

            assertEquals(1, entries.size)
            assertEquals("text", entries.single().messageIdHex)
        }

    private fun timelineMessage(record: AppMessageRecordFfi) =
        dev.ipf.whitenoise.android.state.TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = dev.ipf.whitenoise.android.state.MessageStatus.Received,
        )

    private fun message(
        id: String = "message",
        plaintext: String,
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = byteArrayOf(),
            ),
        kind = 9uL,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )
}
