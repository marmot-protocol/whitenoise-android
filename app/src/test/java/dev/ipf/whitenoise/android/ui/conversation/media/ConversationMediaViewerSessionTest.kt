package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationMediaViewerSessionTest {
    private val owner =
        ConversationMediaViewerOwner(
            accountRef = "personal",
            conversationId = "group-a",
            runtimeGeneration = 7,
        )

    /** Verifies live metadata and content upgrades refresh a logical attachment without replacing its session. */
    @Test
    fun sameLogicalAttachmentKeepsItsSessionAcrossReferenceUpgrade() {
        val state = ConversationMediaViewerSessionState(owner)
        assertTrue(state.open(request(messageId = "message-a", attachmentIndex = 2, sourceEpoch = 0uL)))
        val opened = requireNotNull(state.active)

        assertTrue(state.open(request(messageId = "message-a", attachmentIndex = 2, sourceEpoch = 9uL)))

        val upgraded = requireNotNull(state.active)
        assertEquals(opened.sessionId, upgraded.sessionId)
        assertEquals(opened.selectedAttachment, upgraded.selectedAttachment)
        assertEquals(
            9uL,
            upgraded.request.attachments
                .single()
                .value.sourceEpoch,
        )

        assertTrue(
            state.open(
                request(
                    messageId = "message-a",
                    attachmentIndex = 2,
                    sourceEpoch = 10uL,
                    plaintextSha256 = "dd".repeat(32),
                ),
            ),
        )

        val changedContent = requireNotNull(state.active)
        assertEquals(opened.sessionId, changedContent.sessionId)
        assertEquals(opened.selectedAttachment, changedContent.selectedAttachment)
        assertEquals(
            "dd".repeat(32),
            changedContent.request.attachments
                .single()
                .value.plaintextSha256,
        )
    }

    /** Retains selection/player identity across epoch changes and re-keys for file or crypto changes. */
    @Test
    fun sourceEpochUpgradeKeepsSelectionAndRekeysOnlyForPlaybackIdentityChanges() {
        val state = ConversationMediaViewerSessionState(owner)
        val fallback = request(messageId = "message-a", attachmentIndex = 0, sourceEpoch = 0uL)
        assertTrue(state.open(fallback))
        val sessionId = requireNotNull(state.active).sessionId
        val selectedSibling = ConversationMediaViewerAttachmentId("message-b", 3)
        assertTrue(state.selectPage(sessionId, selectedSibling))

        val authoritative = request(messageId = "message-a", attachmentIndex = 0, sourceEpoch = 9uL)
        assertTrue(state.open(authoritative))

        val upgraded = requireNotNull(state.active)
        assertEquals(sessionId, upgraded.sessionId)
        assertEquals(selectedSibling, upgraded.selectedAttachment)
        val retainedFile = File("/cache/message-a-0-9.mp4")
        assertEquals(
            videoViewerPlayerKey(retainedFile, "message-a", 0, fallback.attachments.single().value),
            videoViewerPlayerKey(retainedFile, "message-a", 0, authoritative.attachments.single().value),
        )
        assertFalse(
            videoViewerPlayerKey(retainedFile, "message-a", 0, authoritative.attachments.single().value) ==
                videoViewerPlayerKey(
                    File("/cache/message-a-0-10.mp4"),
                    "message-a",
                    0,
                    authoritative.attachments.single().value,
                ),
        )
        assertFalse(
            videoViewerPlayerKey(retainedFile, "message-a", 0, authoritative.attachments.single().value) ==
                videoViewerPlayerKey(
                    retainedFile,
                    "message-a",
                    0,
                    reference(sourceEpoch = 9uL, plaintextSha256 = "dd".repeat(32)),
                ),
        )
        assertFalse(
            videoViewerPlayerKey(retainedFile, "message-a", 0, authoritative.attachments.single().value) ==
                videoViewerPlayerKey(
                    retainedFile,
                    "message-a",
                    0,
                    reference(sourceEpoch = 9uL, version = EncryptedMediaVersionFfi.V2),
                ),
        )
    }

    /** Verifies pager selection changes only the active generation's logical attachment. */
    @Test
    fun pageSelectionKeepsTheActiveGeneration() {
        val state = ConversationMediaViewerSessionState(owner)
        state.open(request(messageId = "message-a", attachmentIndex = 0))
        val sessionId = requireNotNull(state.active).sessionId

        assertTrue(
            state.selectPage(
                sessionId = sessionId,
                attachment = ConversationMediaViewerAttachmentId("message-b", 4),
            ),
        )
        assertEquals(
            ConversationMediaViewerAttachmentId("message-b", 4),
            requireNotNull(state.active).selectedAttachment,
        )
    }

    /** Verifies repeated image/video paging stays inside the same mixed-media session. */
    @Test
    fun videoPagedFromAnImageKeepsTheMixedMediaSession() {
        val state = ConversationMediaViewerSessionState(owner)
        val request =
            ConversationMediaViewerOpenRequest(
                messageIdHex = "mixed-message",
                attachments =
                    listOf(
                        IndexedValue(0, reference(mediaType = "image/jpeg", fileName = "still.jpg")),
                        IndexedValue(1, reference(mediaType = "video/mp4", fileName = "clip.mp4")),
                        IndexedValue(2, reference(mediaType = "image/png", fileName = "second-still.png")),
                    ),
                tappedAttachmentIndex = 0,
                sender = "sender",
                recordedAt = 1uL,
                mine = false,
            )
        assertTrue(state.open(request))
        val sessionId = requireNotNull(state.active).sessionId

        assertTrue(
            state.selectPage(
                sessionId = sessionId,
                attachment = ConversationMediaViewerAttachmentId("mixed-message", 1),
            ),
        )

        assertEquals(sessionId, requireNotNull(state.active).sessionId)
        assertEquals(
            ConversationMediaViewerAttachmentId("mixed-message", 1),
            requireNotNull(state.active).selectedAttachment,
        )
        assertTrue(
            state.selectPage(
                sessionId = sessionId,
                attachment = ConversationMediaViewerAttachmentId("mixed-message", 2),
            ),
        )
        assertTrue(
            state.selectPage(
                sessionId = sessionId,
                attachment = ConversationMediaViewerAttachmentId("mixed-message", 1),
            ),
        )
        assertEquals(sessionId, requireNotNull(state.active).sessionId)
        assertEquals(
            ConversationMediaViewerAttachmentId("mixed-message", 1),
            requireNotNull(state.active).selectedAttachment,
        )
    }

    /** Verifies callbacks carrying an obsolete session id cannot mutate or dismiss its replacement. */
    @Test
    fun staleCallbacksCannotSelectOrDismissANewerSession() {
        val state = ConversationMediaViewerSessionState(owner)
        state.open(request(messageId = "old-message", attachmentIndex = 0))
        val oldSessionId = requireNotNull(state.active).sessionId
        state.open(request(messageId = "new-message", attachmentIndex = 1))
        val newSession = requireNotNull(state.active)

        assertFalse(
            state.selectPage(
                sessionId = oldSessionId,
                attachment = ConversationMediaViewerAttachmentId("old-message", 3),
            ),
        )
        assertFalse(state.dismiss(oldSessionId))

        assertEquals(newSession, state.active)
        assertTrue(state.dismiss(newSession.sessionId))
        assertNull(state.active)
        assertFalse(state.dismiss(newSession.sessionId))
    }

    /** Verifies account, conversation, and runtime owner changes create closed viewer state. */
    @Test
    fun newOwnerAndOwnerWithoutAnAccountFailClosed() {
        val current = ConversationMediaViewerSessionState(owner)
        current.open(request(messageId = "message-a", attachmentIndex = 0))

        val anotherConversation =
            ConversationMediaViewerSessionState(owner.copy(conversationId = "group-b"))
        val anotherAccount =
            ConversationMediaViewerSessionState(owner.copy(accountRef = "work"))
        val recreated = ConversationMediaViewerSessionState(owner)
        val missingAccount =
            ConversationMediaViewerSessionState(owner.copy(accountRef = null))

        assertNull(anotherConversation.active)
        assertNull(anotherAccount.active)
        assertNull(recreated.active)
        assertFalse(missingAccount.open(request(messageId = "message-a", attachmentIndex = 0)))
        assertNull(missingAccount.active)
    }

    /** Rejects malformed opens and copies accepted attachment lists away from caller mutation. */
    @Test
    fun invalidRequestsFailClosedAndAcceptedAttachmentsAreDefensivelyCopied() {
        val state = ConversationMediaViewerSessionState(owner)
        assertFalse(state.open(request(messageId = "", attachmentIndex = 0)))
        assertFalse(state.open(request(messageId = "message-a", attachmentIndex = -1)))
        val attachments = mutableListOf(IndexedValue(2, reference()))
        val accepted =
            ConversationMediaViewerOpenRequest(
                messageIdHex = "message-a",
                attachments = attachments,
                tappedAttachmentIndex = 2,
                sender = "sender",
                recordedAt = 1uL,
                mine = false,
            )

        assertTrue(state.open(accepted))
        attachments.clear()

        assertEquals(1, requireNotNull(state.active).request.attachments.size)
        assertEquals(2, requireNotNull(state.active).selectedAttachment.attachmentIndex)
    }

    /** Creates one logical video open at a selectable source epoch. */
    private fun request(
        messageId: String,
        attachmentIndex: Int,
        sourceEpoch: ULong = 1uL,
        plaintextSha256: String = "bb".repeat(32),
    ) = ConversationMediaViewerOpenRequest(
        messageIdHex = messageId,
        attachments =
            listOf(
                IndexedValue(
                    attachmentIndex,
                    reference(sourceEpoch = sourceEpoch, plaintextSha256 = plaintextSha256),
                ),
            ),
        tappedAttachmentIndex = attachmentIndex,
        sender = "sender",
        recordedAt = 1uL,
        mine = false,
    )

    /** Creates a video reference with independently variable content identity fields. */
    private fun reference(
        sourceEpoch: ULong = 1uL,
        mediaType: String = "video/mp4",
        fileName: String = "clip.mp4",
        plaintextSha256: String = "bb".repeat(32),
        version: EncryptedMediaVersionFfi = EncryptedMediaVersionFfi.V1,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = plaintextSha256,
        nonceHex = "cc".repeat(12),
        fileName = fileName,
        mediaType = mediaType,
        version = version,
        sourceEpoch = sourceEpoch,
        dim = "1920x1080",
        thumbhash = null,
    )
}
