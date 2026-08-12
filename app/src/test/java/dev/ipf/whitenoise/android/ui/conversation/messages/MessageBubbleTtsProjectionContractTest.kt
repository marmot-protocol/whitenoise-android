package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.core.MessageProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleTtsProjectionContractTest {
    @Test
    fun legacyMessageIdOnlyCandidateWouldStayActiveAfterDelete() {
        val activePassage = passage()
        // The old gate ignored deletion and considered only the passage's message id.
        val legacyCandidate = activePassage.messageIdHex == MESSAGE_ID

        assertTrue(legacyCandidate)
        assertNull(
            messageBubbleTtsSpeakableIdentity(
                bodyText = DELETED_BODY,
                deleted = true,
                persistedFailure = false,
            ),
        )
        assertFalse(
            messageBubbleTtsProjectionCandidate(
                gateInput(
                    deleted = true,
                    speakableIdentity = null,
                ),
            ),
        )
    }

    @Test
    fun deletedDisplayedBodyBlocksSpeakableIdentityAndProjectionCandidate() {
        assertNotNull(MessageProjector.copyableText(record(), editedText = null))

        val identity =
            messageBubbleTtsSpeakableIdentity(
                bodyText = DELETED_BODY,
                deleted = true,
                persistedFailure = false,
            )
        val gateInput =
            gateInput(
                deleted = true,
                speakableIdentity = identity,
            )
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = progress(),
            )

        assertNull(identity)
        assertFalse(messageBubbleTtsProjectionCandidate(gateInput))
        assertFalse(state.candidate)
        assertNull(state.effectivePassage)
        assertNull(state.effectiveProgress)
    }

    @Test
    fun persistedFailureBlocksSpeakableIdentityAndProjectionCandidate() {
        val identity =
            messageBubbleTtsSpeakableIdentity(
                bodyText = INVALIDATED_BODY,
                deleted = false,
                persistedFailure = true,
            )
        val gateInput =
            gateInput(
                persistedFailure = true,
                speakableIdentity = identity,
            )
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = progress(),
            )

        assertNull(identity)
        assertFalse(messageBubbleTtsProjectionCandidate(gateInput))
        assertFalse(state.candidate)
        assertNull(state.effectivePassage)
        assertNull(state.effectiveProgress)
    }

    @Test
    fun textSelectionModeBlocksProjectionCandidate() {
        val identity = speakableIdentity(bodyText = "Hello world")
        val gateInput =
            gateInput(
                textSelectionMode = true,
                speakableIdentity = identity,
            )

        assertFalse(messageBubbleTtsProjectionCandidate(gateInput))
        assertNull(
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = progress(),
            ).effectivePassage,
        )
    }

    @Test
    fun messageMismatchBlocksProjectionCandidate() {
        val identity = speakableIdentity(bodyText = "Hello world")
        val gateInput =
            gateInput(
                messageIdHex = MESSAGE_ID,
                passageMessageIdHex = OTHER_MESSAGE_ID,
                speakableIdentity = identity,
            )

        assertFalse(messageBubbleTtsProjectionCandidate(gateInput))
    }

    @Test
    fun projectionMismatchSuppressesPassageAndProgress() {
        val identity = speakableIdentity(bodyText = "Hello world")
        val gateInput = gateInput(speakableIdentity = identity)
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = "current-projection",
                progress = progress(),
            )

        assertTrue(state.candidate)
        assertNull(state.effectivePassage)
        assertNull(state.effectiveProgress)
    }

    @Test
    fun matchingProjectionKeepsPassageAndProgress() {
        val identity = speakableIdentity(bodyText = "Hello world")
        val gateInput = gateInput(speakableIdentity = identity)
        val expectedProgress = progress()
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = expectedProgress,
            )

        assertTrue(state.candidate)
        assertEquals(passage(), state.effectivePassage)
        assertEquals(expectedProgress, state.effectiveProgress)
    }

    private fun gateInput(
        messageIdHex: String = MESSAGE_ID,
        passageMessageIdHex: String = MESSAGE_ID,
        textSelectionMode: Boolean = false,
        deleted: Boolean = false,
        persistedFailure: Boolean = false,
        speakableIdentity: MessageBubbleTtsSpeakableIdentity? = speakableIdentity(),
    ) = MessageBubbleTtsGateInput(
        messageIdHex = messageIdHex,
        ttsHighlightPassage = passage(messageIdHex = passageMessageIdHex),
        textSelectionMode = textSelectionMode,
        deleted = deleted,
        persistedFailure = persistedFailure,
        speakableIdentity = speakableIdentity,
    )

    private fun speakableIdentity(bodyText: String = "Hello **world**") = MessageBubbleTtsSpeakableIdentity(bodyText)

    private fun passage(messageIdHex: String = MESSAGE_ID) =
        TtsPassage(
            messageIdHex = messageIdHex,
            sentenceIndex = 0,
            projectionId = MATCHING_PROJECTION_ID,
        )

    private fun progress() =
        TtsReadAloudProgress(
            sentenceIndex = 1,
            sentenceCount = 3,
            messageIndex = 0,
            messageCount = 2,
        )

    private fun record() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Hello **world**",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines = listOf(MarkdownInlineFfi.Text("Hello **world**")),
                            ),
                        ),
                ),
            kind = 9uL,
            tags = emptyList<MessageTagFfi>(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private companion object {
        val MESSAGE_ID = "05" + "00".repeat(31)
        val OTHER_MESSAGE_ID = "06" + "00".repeat(31)
        const val MATCHING_PROJECTION_ID = "shared-projection"
        const val DELETED_BODY = "Deleted a message"
        const val INVALIDATED_BODY = "Message could not be decrypted"
    }
}
