package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.media.editor.nativeHandleStub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins how a send of stored draft files uploads from the draft by id and leaves admission to the draft send. */
class DraftFilesUploadTest {
    /** A matching draft saves the caption first, then uploads its stored file by id under that same revision. */
    @Test
    fun matchingDraftUploadsStoredFilesUnderTheCaptionRevision() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("old caption", stored("clip")))

            val outcome = engine.marmot.uploadComposerDraftFilesWithToken("acct", "group", send("clip"), control(), "t")

            assertEquals(listOf("localSendStatus", "selectedMessageDraft", "save", "upload"), engine.calls)
            assertEquals(listOf(MessageDraftAttachmentInputFfi.Retained("clip")), engine.savedInputs)
            assertEquals("new caption", engine.savedContent)
            assertSame(engine.savedRevision, engine.uploadRevision)
            assertEquals(listOf("clip"), engine.uploadedIds)
            assertNull(outcome.acceptance)
        }

    /** A draft that already carries the caption is uploaded as it stands, with no extra save. */
    @Test
    fun unchangedCaptionUploadsWithoutSaving() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))

            engine.marmot.uploadComposerDraftFilesWithToken("acct", "group", send("clip"), control(), "t")

            assertEquals(listOf("localSendStatus", "selectedMessageDraft", "upload"), engine.calls)
            assertSame(engine.selectedRevision, engine.uploadRevision)
        }

    /** A draft that no longer holds exactly the send's stored files, or replies, fails before anything uploads. */
    @Test
    fun draftThatNoLongerMatchesFailsBeforeUploading() =
        runBlocking {
            for (draft in listOf(
                null,
                content("new caption", stored("other")),
                content("new caption", stored("clip"), stored("extra")),
                content("new caption", stored("clip")).copy(replyToMessageIdHex = "a".repeat(64)),
            )) {
                val engine = DraftUploadEngine(draft = draft)

                val failure =
                    runCatching {
                        engine.marmot.uploadComposerDraftFilesWithToken("acct", "group", send("clip"), control(), "t")
                    }.exceptionOrNull()

                assertTrue("draft $draft must refuse the send", failure is IllegalStateException)
                assertTrue(engine.uploadedIds.isEmpty())
            }
        }

    /** A send MDK already owns is recovered as pending without reading the draft or uploading again. */
    @Test
    fun recoveredSendNeverUploadsAgain() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")), recovered = true)

            val outcome = engine.marmot.uploadComposerDraftFilesWithToken("acct", "group", send("clip"), control(), "t")

            assertEquals(listOf("localSendStatus"), engine.calls)
            assertTrue(outcome.recoveredWithoutUpload)
            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, outcome.acceptance?.acceptDisposition)
        }

    /** Only a send whose every attachment is a stored draft file, in the draft's order, maps to the draft's ids. */
    @Test
    fun draftIdsRequireEveryAttachmentStoredInOrder() {
        val draft = content("", stored("a"), stored("b"))

        assertEquals(listOf("a", "b"), draftFileIdsFor(draft, send("a", "b").attachments))
        assertNull(draftFileIdsFor(draft, send("b", "a").attachments))
        assertNull(draftFileIdsFor(draft, listOf(storedPick("a"), inlinePick("b"))))
    }

    /** A send of stored picks named [ids], with the caption the composer sends. */
    private fun send(vararg ids: String) = DraftFilesUpload(ids.map(::storedPick), "new caption")

    /** A pick the draft stores as file [id]. */
    private fun storedPick(id: String) =
        PendingAttachment(
            plaintextBytes = ByteArray(0),
            mediaType = "video/mp4",
            fileName = "$id.mp4",
            draftFile = DraftStoredFile(id, 900L),
        )

    /** An in-memory pick that happens to share [id]'s name and type. */
    private fun inlinePick(id: String) = PendingAttachment(byteArrayOf(1), "video/mp4", "$id.mp4")

    /** A stored-file descriptor for [id]. */
    private fun stored(id: String) =
        SelectedMessageDraftAttachmentFfi(
            id = id,
            fileName = "$id.mp4",
            mediaType = "video/mp4",
            plaintextSize = 900uL,
            dim = null,
            thumbhash = null,
            durationSeconds = null,
            waveformSamples = emptyList(),
            fileBacked = true,
        )

    /** Draft content holding [attachments] under [text]. */
    private fun content(
        text: String,
        vararg attachments: SelectedMessageDraftAttachmentFfi,
    ) = SelectedMessageDraftContentFfi("group", text, null, attachments.toList(), 1L, 1L)

    /** A transfer control that needs no native library. */
    private fun control() = nativeHandleStub(MediaFileTransferControlFfi::class.java)
}

/** Answers the draft calls a stored-file upload makes and records what it was asked. */
private class DraftUploadEngine(
    draft: SelectedMessageDraftContentFfi?,
    private val recovered: Boolean = false,
) {
    val selectedRevision: MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)
    val savedRevision: MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)
    private var current = draft
    val calls = mutableListOf<String>()
    var savedInputs: List<MessageDraftAttachmentInputFfi>? = null
    var savedContent: String? = null
    var uploadRevision: MessageDraftRevisionFfi? = null
    var uploadedIds: List<String> = emptyList()

    val marmot: MarmotInterface = Engine()

    /** Overrides answer directly, so MarmotKit's exceptions reach the code under test unwrapped. */
    private inner class Engine : MarmotInterface by unexpectedCalls() {
        /** Reports a completed native send when the test recovers one. */
        override fun localSendStatus(
            accountRef: String,
            groupIdHex: String,
            clientToken: String,
        ): LocalSendStatusFfi? {
            calls += "localSendStatus"
            return if (recovered) LocalSendStatusFfi.Completed(summary()) else null
        }

        /** Returns the scripted draft under its first revision. */
        override fun selectedMessageDraft(
            accountRef: String,
            groupIdHex: String,
        ): SelectedMessageDraftFfi {
            calls += "selectedMessageDraft"
            return SelectedMessageDraftFfi(selectedRevision, current)
        }

        /** Records the caption save and moves the draft to its second revision. */
        override fun saveMessageDraftInputsIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            content: String,
            replyToMessageIdHex: String?,
            mediaAttachments: List<MessageDraftAttachmentInputFfi>,
        ): SelectedMessageDraftFfi {
            calls += "save"
            savedInputs = mediaAttachments
            savedContent = content
            current = current?.copy(content = content)
            return SelectedMessageDraftFfi(savedRevision, current)
        }

        /** Records the ids and revision an upload names. */
        override suspend fun uploadMessageDraftAttachments(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachmentIds: List<String>,
            blossomServer: String?,
            control: MediaFileTransferControlFfi,
        ): MediaUploadResultFfi {
            calls += "upload"
            uploadRevision = revision
            uploadedIds = attachmentIds
            return MediaUploadResultFfi(emptyList(), null)
        }
    }

    /** A published summary for a recovered send. */
    private fun summary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf("m"),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )
}

/** Fails any binding call a stored-file upload test did not expect. */
private fun unexpectedCalls(): MarmotInterface =
    nativeBoundary { method, _ ->
        error("unexpected native call $method")
    }
