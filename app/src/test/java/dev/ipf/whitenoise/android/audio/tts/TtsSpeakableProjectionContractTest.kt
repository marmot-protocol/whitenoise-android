package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.ui.conversation.messages.messageSpeakableProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TtsSpeakableProjectionContractTest {
    @Test
    fun editedPlainMessageUiProjectionMatchesTtsQueue() =
        runBlocking {
            val emptyDocument = emptyDocument()
            val record = message(plaintext = "Original hello", contentTokens = emptyDocument)
            val editedText = "Edited plain hello"

            val ttsEntry =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = editedText,
                    senderDisplayName = "Alice",
                    parseMarkdown = { emptyDocument },
                )!!
            val uiProjection =
                messageSpeakableProjection(
                    bodyText = editedText,
                    document = emptyDocument,
                    mentionDisplayName = null,
                    isGroupMember = null,
                )!!

            assertEquals(ttsEntry.projectionId, uiProjection.projectionId)
            assertEquals(ttsEntry.text, uiProjection.text)
        }

    @Test
    fun editedMarkdownMessageUiProjectionMatchesTtsQueue() =
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
            val editedText = "Edited *value*"

            val ttsEntry =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = editedText,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument },
                )!!
            val uiProjection =
                messageSpeakableProjection(
                    bodyText = editedText,
                    document = editedDocument,
                    mentionDisplayName = null,
                    isGroupMember = null,
                )!!

            assertEquals(ttsEntry.projectionId, uiProjection.projectionId)
            assertEquals(ttsEntry.text, uiProjection.text)
            assertNotEquals(originalDocument, editedDocument)
        }

    @Test
    fun editedMarkdownMessageLegacyUiProjectionDoesNotMatchTtsQueue() =
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
            val editedText = "Edited *value*"

            val ttsEntry =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = editedText,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument },
                )!!
            val legacyUiProjection =
                messageSpeakableProjection(
                    bodyText = editedText,
                    document = emptyDocument(),
                    mentionDisplayName = null,
                    isGroupMember = null,
                )

            assertNotNull(legacyUiProjection)
            assertNotEquals(ttsEntry.projectionId, legacyUiProjection!!.projectionId)
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

    private fun emptyDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = byteArrayOf(),
        )

    private fun message(
        plaintext: String,
        contentTokens: MarkdownDocumentFfi,
    ) = AppMessageRecordFfi(
        messageIdHex = "message",
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
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
