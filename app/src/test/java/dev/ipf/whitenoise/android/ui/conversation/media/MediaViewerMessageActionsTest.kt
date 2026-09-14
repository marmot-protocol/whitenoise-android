package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises the production page/owner boundary and the native whole-message eligibility path. */
class MediaViewerMessageActionsTest {
    private val owner = ConversationMediaViewerOwner("account-a", "group", 1)
    private val references = listOf(reference("photo.jpg"), reference("second.jpg"))
    private val page = MediaViewerPage("album", 1, references[1], false, "alice", 1uL)

    /** A selected second photo must preserve both original source indices and the edited native caption. */
    @Test
    fun forwardingSecondPagePreservesWholeAlbumAndOriginalIndices() {
        val payload =
            mediaViewerForwardPayload(
                page,
                item(),
                references,
                true,
                emptySet(),
                100uL,
                "edited caption",
            ) as ForwardMessagePayload.Media
        assertEquals("album", payload.sourceMessageIdHex)
        assertEquals("group", payload.sourceGroupIdHex)
        assertEquals(listOf(0, 1), payload.attachments.map { it.attachmentIndex })
        assertEquals(references, payload.attachments.map { it.reference })
        assertEquals("edited caption", payload.caption)
    }

    /** Eligibility is recomputed at action time rather than trusting previously painted controls. */
    @Test
    fun removedReadOnlyPendingFailedAndExpiredSourcesCannotEnterPicker() {
        assertNull(mediaViewerForwardPayload(page, item(), references, false, emptySet(), 100uL))
        for (status in listOf(MessageStatus.Pending, MessageStatus.Failed, MessageStatus.Streaming)) {
            assertNull(
                mediaViewerForwardPayload(page, item().copy(status = status), references, true, emptySet(), 100uL),
            )
        }
        val expired = item().let { it.copy(record = it.record.copy(retentionExpiresAt = 100uL)) }
        assertNull(mediaViewerForwardPayload(page, expired, references, true, emptySet(), 100uL))
        assertNull(mediaViewerForwardPayload(page, item(), references.take(1), true, emptySet(), 100uL))
    }

    /** Source epoch upgrades cannot dispatch a stale reference through a newly rendered page. */
    @Test
    fun sourceEpochChangeRequiresTheCurrentAuthoritativePage() {
        val upgraded = references.map { it.copy(sourceEpoch = 8uL) }
        assertNull(mediaViewerForwardPayload(page, item(), upgraded, true, emptySet(), 100uL))
        val current = page.copy(reference = upgraded[1])
        val oldRecord = item().let { it.copy(record = it.record.copy(sourceEpoch = 1uL)) }
        assertNull(mediaViewerForwardPayload(current, oldRecord, upgraded, true, emptySet(), 100uL))
        val authoritative = oldRecord.copy(record = oldRecord.record.copy(sourceEpoch = 8uL))
        val payload =
            mediaViewerForwardPayload(current, authoritative, upgraded, true, emptySet(), 100uL)
                as ForwardMessagePayload.Media
        assertEquals(8uL, payload.attachments[1].reference.sourceEpoch)
    }

    /** Captured actions reject page, mine, reference, owner, runtime, and disposed-session replacements. */
    @Test
    fun capturedActionsRejectEveryReplacementAndDispose() {
        val gate = MediaViewerActionGate(owner)
        val delivered = mutableListOf<MediaViewerPage>()
        gate.currentPage = page
        gate.dispatch(page, owner) { delivered += it }
        for (replacement in listOf(
            page.copy(messageIdHex = "another"),
            page.copy(attachmentIndex = 0),
            page.copy(mine = true),
            page.copy(reference = page.reference.copy(sourceEpoch = 9uL)),
        )) {
            gate.currentPage = replacement
            gate.dispatch(page, owner) { delivered += it }
        }
        gate.currentPage = page
        gate.dispatch(page, owner.copy(accountRef = "account-b")) { delivered += it }
        gate.dispatch(page, owner.copy(conversationId = "other")) { delivered += it }
        gate.dispatch(page, owner.copy(runtimeGeneration = 2)) { delivered += it }
        gate.close()
        gate.dispatch(page, owner) { delivered += it }
        assertEquals(listOf(page), delivered)
    }

    /** Builds a list item fixture. */
    private fun item(): TimelineMessage {
        val record =
            AppMessageRecordFfi(
                messageIdHex = "album",
                direction = "received",
                groupIdHex = "group",
                sender = "alice",
                plaintext = "caption",
                contentTokens = MarkdownDocumentFfi(emptyList(), false, ByteArray(0)),
                kind = 9uL,
                tags = listOf(MessageTagFfi(listOf("imeta", "first")), MessageTagFfi(listOf("imeta", "second"))),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
        return TimelineMessage("album", record, MessageStatus.Received)
    }

    /** Builds a media reference fixture. */
    private fun reference(name: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/blob")),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = name,
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
}
