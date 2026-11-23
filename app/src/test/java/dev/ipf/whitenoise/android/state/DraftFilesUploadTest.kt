package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
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
            assertSame(engine.savedRevision, outcome.draftRevision)
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
                content("new caption", stored("clip").copy(fileBacked = false)),
                content("new caption", stored("clip").copy(plaintextSize = 901uL)),
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
        val inlineUnderStoredId = content("", stored("a"), stored("b").copy(fileBacked = false))
        assertNull(draftFileIdsFor(inlineUnderStoredId, send("a", "b").attachments))
    }

    /** Uploaded stored files are admitted under the revision they uploaded from, without reading the draft again. */
    @Test
    fun admissionUsesTheUploadRevision() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))

            engine.marmot.sendComposerDraftFiles("acct", "group", emptyList(), send("clip"), admission(engine.uploaded))

            assertEquals(listOf("localSendStatus", "admit"), engine.calls)
            assertSame(engine.uploaded, engine.admittedRevision)
        }

    /** A save after the upload moved the draft: the same stored files are admitted under the draft's new revision. */
    @Test
    fun movedDraftWithTheSameFilesIsAdmittedUnderItsNewRevision() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("typed later", stored("clip")), staleRevisions = 1)

            engine.marmot.sendComposerDraftFiles("acct", "group", emptyList(), send("clip"), admission(engine.uploaded))

            assertEquals("new caption", engine.savedContent)
            assertSame(engine.savedRevision, engine.admittedRevision)
        }

    /** A moved draft that no longer holds exactly the uploaded files keeps the conflict, so a retry uploads again. */
    @Test
    fun movedDraftWithOtherFilesKeepsTheConflict() =
        runBlocking {
            for (draft in listOf(content("", stored("other")), content("", stored("clip").copy(plaintextSize = 1uL)))) {
                val engine = DraftUploadEngine(draft = draft, staleRevisions = 1)

                val failure =
                    runCatching {
                        engine.marmot.sendComposerDraftFiles(
                            "acct",
                            "group",
                            emptyList(),
                            send("clip"),
                            admission(engine.uploaded),
                        )
                    }.exceptionOrNull()

                assertTrue(failure is MarmotKitException.MessageDraftRevisionConflict)
                assertNull(engine.admittedRevision)
            }
        }

    /** The admission for files uploaded under [revision] with the send's token. */
    private fun admission(revision: MessageDraftRevisionFfi) = DraftFilesAdmission(revision, "t")

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
    private var staleRevisions: Int = 0,
) {
    val selectedRevision: MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)
    val savedRevision: MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)

    // The revision a test's earlier upload used.
    val uploaded: MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)
    var admittedRevision: MessageDraftRevisionFfi? = null
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

        /** Admits the send under [revision], refusing the first [staleRevisions] attempts as a moved draft. */
        override suspend fun sendMessageDraftWithClientToken(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachments: List<MediaAttachmentReferenceFfi>,
            clientToken: String,
        ): LocalSendAcceptanceFfi {
            calls += "admit"
            if (staleRevisions-- > 0) throw MarmotKitException.MessageDraftRevisionConflict()
            admittedRevision = revision
            return LocalSendAcceptanceFfi(clientToken, "m".repeat(64))
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
