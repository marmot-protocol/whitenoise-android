package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
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
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.pdf.pdfFixture
import dev.ipf.whitenoise.android.ui.conversation.CollectionTokenStatus
import dev.ipf.whitenoise.android.ui.conversation.restoreCollectionOperations
import dev.ipf.whitenoise.android.ui.profile.personTestGroup
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins how a send of stored draft files uploads from the draft by id and admits without overwriting a newer draft. */
class DraftFilesUploadTest {
    @get:Rule val verificationFiles = TemporaryFolder()

    /** A draft that says exactly the send uploads its stored file by id, saves nothing and may be consumed. */
    @Test
    fun exactDraftUploadsByIdAndMayBeConsumed() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))
            val captured = engine.revision

            val outcome = engine.upload(send("clip"))

            assertEquals(listOf("localSendStatus", "selectedMessageDraft", "read", "upload"), engine.calls)
            assertSame(captured, engine.uploadRevision)
            assertEquals(listOf("clip"), engine.uploadedIds)
            assertSame(captured, outcome.draftRevision)
            assertNull(outcome.acceptance)
        }

    /** Text typed or an attachment added before the upload is left alone, and that draft is never consumed. */
    @Test
    fun newerDraftUploadsTheHeldFilesWithoutSavingOrConsuming() =
        runBlocking {
            for (draft in listOf(
                content("typed later", stored("clip")),
                content("new caption", stored("clip"), stored("added")),
                content("new caption", stored("clip")).copy(replyToMessageIdHex = "a".repeat(64)),
            )) {
                val engine = DraftUploadEngine(draft = draft)

                val outcome =
                    engine.upload(send("clip"))

                assertEquals(listOf("clip"), engine.uploadedIds)
                assertFalse("draft $draft must not be saved", "save" in engine.calls)
                assertNull("draft $draft must not be consumed", outcome.draftRevision)
            }
        }

    /** A draft that no longer holds the send's stored file, as the same stored file, fails before anything uploads. */
    @Test
    fun draftWithoutTheStoredFileFailsBeforeUploading() =
        runBlocking {
            for (draft in listOf(
                null,
                content("new caption", stored("other")),
                content("new caption", stored("clip").copy(fileBacked = false)),
                content("new caption", stored("clip").copy(plaintextSize = 901uL)),
                content("new caption", stored("clip").copy(mediaType = "image/png")),
            )) {
                val engine = DraftUploadEngine(draft = draft)

                val failure =
                    runCatching {
                        engine.upload(send("clip"))
                    }.exceptionOrNull()

                assertTrue("draft $draft must refuse the send", failure is IllegalStateException)
                assertTrue(engine.uploadedIds.isEmpty())
            }
        }

    /** A send MDK already owns is recovered without uploading again, and its leftover file leaves the draft. */
    @Test
    fun recoveredSendNeverUploadsAgainAndConsumesItsLeftovers() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("typed later", stored("clip")), recovered = true)

            val outcome = engine.upload(send("clip"))

            assertTrue(outcome.recoveredWithoutUpload)
            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, outcome.acceptance?.acceptDisposition)
            assertTrue(engine.uploadedIds.isEmpty())
            assertEquals(content("typed later"), engine.current)
        }

    /** Ids map only when the draft holds every attachment as a stored file, whatever else it holds. */
    @Test
    fun heldIdsRequireEveryAttachmentStored() {
        val draft = content("", stored("a"), stored("extra"), stored("b"))

        assertEquals(listOf("a", "b"), heldDraftFileIds(draft, send("a", "b").attachments))
        assertEquals(listOf("b", "a"), heldDraftFileIds(draft, send("b", "a").attachments))
        assertNull(heldDraftFileIds(draft, listOf(storedPick("a"), inlinePick("b"))))
        val inlineUnderStoredId = content("", stored("a"), stored("b").copy(fileBacked = false))
        assertNull(heldDraftFileIds(inlineUnderStoredId, send("a", "b").attachments))
    }

    /** Only these files and nothing else, in order, under the caption and with no reply, make a draft consumable. */
    @Test
    fun onlyAnExactDraftIsConsumable() {
        val upload = send("a", "b")

        assertTrue(draftIsExactly(content(" new caption ", stored("a"), stored("b")), upload))
        assertFalse(draftIsExactly(content("new caption", stored("b"), stored("a")), upload))
        assertFalse(draftIsExactly(content("new caption", stored("a"), stored("b"), stored("c")), upload))
        assertFalse(draftIsExactly(content("typed later", stored("a"), stored("b")), upload))
        assertFalse(draftIsExactly(null, upload))
    }

    /** An unchanged draft is consumed by the admission itself, with the send's caption and token. */
    @Test
    fun unchangedDraftIsConsumedOnAdmission() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))
            val captured = engine.revision

            engine.marmot.sendComposerDraftFiles("acct", "group", emptyList(), send("clip"), admission(captured))

            assertEquals("new caption", engine.admittedCaption)
            assertSame(captured, engine.admittedConsuming)
            assertNull(engine.current)
        }

    /**
     * A slow upload followed by a newer saved draft: the send is admitted with its own caption, the newer text is never
     * overwritten, and reopening the draft shows that text without the sent file.
     */
    @Test
    fun newerDraftSavedDuringASlowUploadKeepsItsText() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))
            val outcome = engine.upload(send("clip"))
            engine.saveText("typed later")

            val admission = admission(checkNotNull(outcome.draftRevision))
            engine.marmot.sendComposerDraftFiles("acct", "group", emptyList(), send("clip"), admission)

            assertEquals("new caption", engine.admittedCaption)
            assertEquals("t", engine.admittedToken)
            val reopened = engine.marmot.selectedMessageDraft("acct", "group").draft
            assertEquals(content("typed later"), reopened)
        }

    /** Staging another attachment keeps the new pick while the send's still-owned caption is removed. */
    @Test
    fun attachmentAddedDuringTheUploadStays() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))
            val outcome = engine.upload(send("clip"))
            engine.addAttachment(stored("added"))

            engine.marmot.sendComposerDraftFiles(
                "acct",
                "group",
                emptyList(),
                send("clip"),
                DraftFilesAdmission(outcome.draftRevision, "t", ownsCaption = { true }),
            )

            assertEquals(content("", stored("added")), engine.current)
            assertEquals(
                listOf(
                    MessageDraftAttachmentInputFfi.Retained("added"),
                    MessageDraftAttachmentInputFfi.Removed("clip"),
                ),
                engine.savedInputs,
            )
        }

    /** A save that wins the race with the leftover cleanup is read again, and the cleanup still keeps its text. */
    @Test
    fun leftoverCleanupFollowsARacingSave() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("typed later", stored("clip")))
            engine.beforeSave = { engine.saveText("typed even later") }

            engine.marmot.consumeSentDraftFiles("acct", "group", send("clip"))

            assertEquals(content("typed even later"), engine.current)
        }

    /** Owned copies upload as files, upload only, without reading the draft. */
    @Test
    fun ownedCopiesUploadAsFilesWithoutTheDraft() =
        runBlocking {
            val engine = DraftUploadEngine(draft = null)
            val request = MediaFileUploadRequestFfi(emptyList(), "new caption", send = false, blossomServer = null)

            val outcome =
                engine.marmot.uploadOwnedDraftCopiesWithToken("acct", "group", send("clip"), request, control(), "t")

            assertEquals(listOf("localSendStatus", "uploadFiles"), engine.calls)
            assertSame(request, engine.uploadedRequest)
            assertNull(outcome.draftRevision)
        }

    /** A retained copy cannot bypass PDF privacy simply because its original upload failed. */
    @Test
    fun retryOnlyUploadsVerifiedCopies() =
        runBlocking {
            val signed = pdfFixture("signed")
            val clean = pdfFixture("ghostscript").also { PdfMetadataScrubber.scrub(it) }
            for (bytes in listOf(signed, clean)) {
                val copy = verificationFiles.newFile().also { it.writeBytes(bytes) }
                val attachment =
                    MediaFileUploadAttachmentRequestFfi(
                        sourcePath = copy.absolutePath,
                        expectedSize = bytes.size.toULong(),
                        fileName = "clip.mp4",
                        mediaType = "video/mp4",
                        dim = null,
                        thumbhash = null,
                    )
                val request = MediaFileUploadRequestFfi(listOf(attachment), null, send = false, blossomServer = null)
                val engine = DraftUploadEngine(draft = null)
                val upload = send("clip")
                val outcome =
                    runCatching {
                        engine.marmot.uploadOwnedDraftCopiesWithToken("acct", "group", upload, request, control(), "t")
                    }
                if (bytes === signed) {
                    assertTrue(outcome.exceptionOrNull() is UnverifiedStoredDraftPdf)
                    assertNull(engine.uploadedRequest)
                } else {
                    outcome.getOrThrow()
                    assertSame(request, engine.uploadedRequest)
                }
            }
        }

    /** Uploads through the selected-revision path using the per-test verification directory. */
    private suspend fun DraftUploadEngine.upload(upload: DraftFilesUpload): DurableComposerMediaUpload =
        marmot.uploadComposerDraftFilesWithToken("acct", "group", upload, control(), "t", verificationFiles.root)

    /** The admission for files uploaded from [revision], if any, with the send's token. */
    private fun admission(revision: MessageDraftRevisionFfi?) = DraftFilesAdmission(revision, "t")

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

    /** Identical words typed for the next message still belong to a newer revision. */
    @Test
    fun newerIdenticalCaptionIsNotCleared() =
        runBlocking {
            val engine = DraftUploadEngine(draft = content("new caption", stored("clip")))
            val previous = engine.revision
            engine.saveText("new caption")

            engine.marmot.sendComposerDraftFiles("acct", "group", emptyList(), send("clip"), admission(previous))

            assertEquals(content("new caption"), engine.current)
        }

    /** Restored drafts cannot upload a signed PDF disguised as a video, but a scrubbed PDF still uploads. */
    @Test
    fun restoredPdfIsVerifiedFromNativeBytesBeforeUpload() =
        runBlocking {
            val signed = pdfFixture("signed")
            val clean = pdfFixture("ghostscript").also { PdfMetadataScrubber.scrub(it) }
            for (bytes in listOf(signed, clean)) {
                val descriptor = stored("clip").copy(plaintextSize = bytes.size.toULong())
                val engine = DraftUploadEngine(content("new caption", descriptor), fileBody = bytes)
                val pick = storedPick("clip").copy(draftFile = DraftStoredFile("clip", bytes.size.toLong()))
                val upload = DraftFilesUpload(listOf(pick), "new caption")

                val outcome =
                    runCatching {
                        engine.upload(upload)
                    }
                if (bytes === signed) {
                    assertTrue(outcome.exceptionOrNull() is UnverifiedStoredDraftPdf)
                    assertTrue(engine.uploadedIds.isEmpty())
                } else {
                    outcome.getOrThrow()
                    assertEquals(listOf("clip"), engine.uploadedIds)
                }
            }
        }

    /** Moving the caption revision during a clean PDF copy remains a retryable revision conflict. */
    @Test
    fun cleanPdfReadKeepsItsRevisionConflict() =
        runBlocking {
            val body = pdfFixture("ghostscript").also { PdfMetadataScrubber.scrub(it) }
            val descriptor = stored("clip").copy(plaintextSize = body.size.toULong())
            val engine = DraftUploadEngine(content("caption", descriptor), fileBody = body)
            val pick = storedPick("clip").copy(draftFile = DraftStoredFile("clip", body.size.toLong()))
            val upload = DraftFilesUpload(listOf(pick), "caption")
            engine.beforeFullRead = { engine.saveText("later caption") }

            val failure = runCatching { engine.upload(upload) }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.MessageDraftRevisionConflict)
            assertTrue(engine.uploadedIds.isEmpty())
            engine.beforeFullRead = {}
            engine.upload(upload)
            assertEquals(listOf(descriptor.id), engine.uploadedIds)
        }

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

    /** A transfer control that needs no native library. */
    private fun control() = nativeHandleStub(MediaFileTransferControlFfi::class.java)
}

/** Exercises admission and the shelf refresh through the same real controller that owns an in-flight send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StoredDraftAcceptanceTest {
    /** Even after expiry, recovery settles the original native ID that MDK accepted before process death. */
    @Test
    fun acceptedCollectionTokenRecoversOriginalFileWithoutConsumingLaterDraft() =
        runBlocking {
            val old = draftAttachment("sent")
            val replacement = old.copy(id = "picked-again")
            val skipped = old.copy(id = "skipped-for-storage")
            val updatedDraft =
                content("same caption", old, replacement, skipped).copy(replyToMessageIdHex = "new-reply")
            val engine = DraftUploadEngine(updatedDraft)
            val app = acceptanceAppState(engine)
            val journal = AttachmentCollectionJournal(app.appContext.noBackupFilesDir)
            val item =
                CollectionItem(
                    "pick",
                    "content://fixture/file",
                    CollectionItemKind.DOCUMENT,
                    CollectionItemState.UPLOADING,
                    CollectionStoredDraft(old.id, 900L, old.mediaType),
                )
            val skippedItem =
                item.copy(
                    id = "skipped-pick",
                    state = CollectionItemState.SKIPPED,
                    storedDraft = CollectionStoredDraft(skipped.id, 900L, skipped.mediaType),
                )
            val items = listOf(item, skippedItem)
            val chunk =
                CollectionChunk("chunk", 0, items.map { it.id }, true, "accepted", CollectionChunkState.UPLOADING)
            val now = System.currentTimeMillis()
            val expired = now - ATTACHMENT_COLLECTION_JOURNAL_TTL_MS - 1
            val operation =
                AttachmentCollectionOperation(
                    "recovery",
                    "acct",
                    "group",
                    items,
                    listOf(chunk),
                    expired,
                    expired,
                )
            try {
                assertTrue(journal.write(operation))
                app.restoreCollectionOperations("acct", "group", { true }) { CollectionTokenStatus.ACCEPTED }
                awaitAttachmentCollectionJournal()

                assertEquals(updatedDraft.copy(mediaAttachments = listOf(replacement, skipped)), engine.current)
                assertTrue(app.nativeComposerCleanupRevision("acct", "group") > 0)
                assertTrue(journal.read("acct", "group").isEmpty())
            } finally {
                journal.delete("acct", "group", operation.id)
                app.mutationsScope.cancel()
            }
        }

    /** An identical edit captured by the upload cannot consume newer text or leave the reopened shelf stale. */
    @Test
    fun acceptedStoredFileRefreshesOnlyItsOwnShelfWithoutConsumingNewText() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val attachment =
                SelectedMessageDraftAttachmentFfi(
                    id = "clip",
                    fileName = "clip.mp4",
                    mediaType = "video/mp4",
                    plaintextSize = 900uL,
                    dim = null,
                    thumbhash = null,
                    durationSeconds = null,
                    waveformSamples = emptyList(),
                    fileBacked = true,
                )
            val engine = DraftUploadEngine(content("caption", attachment))
            val app = acceptanceAppState(engine)
            try {
                val controller = ConversationController(appState = app, initialGroup = group)
                val pick =
                    PendingAttachment(ByteArray(0), "video/mp4", "clip.mp4", draftFile = DraftStoredFile("clip", 900L))
                val retained =
                    RetainedMediaUpload(listOf(pick), "caption").apply {
                        draftHandling = RetainedDraftHandling.COMPOSER_SELECTION
                    }
                retained.draftSendClearToken = checkNotNull(app.captureDraftForSend("acct", group.groupIdHex))
                app.messageDraftRepository.coordinated.acceptMutation("acct", group.groupIdHex)
                engine.saveText("caption")
                retained.draftUploadRevision = engine.revision
                val reopenedRevision = app.nativeComposerCleanupRevision("acct", group.groupIdHex)

                controller.admitRetainedMedia("acct", emptyList(), retained, "token")

                assertNull(engine.admittedConsuming)
                assertEquals(content("caption"), engine.current)
                assertTrue(app.nativeComposerCleanupRevision("acct", group.groupIdHex) > reopenedRevision)
                assertEquals(0L, app.nativeComposerCleanupRevision("other", group.groupIdHex))
            } finally {
                app.mutationsScope.cancel()
            }
        }

    /** A single-file write-ahead record survives accepted admission when native draft cleanup is unavailable. */
    @Test
    fun acceptedSingleStoredFileRecoversAfterCleanupWasInterrupted() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val original = draftAttachment("sent")
            val newer = original.copy(id = "picked-again")
            val updated = content("later caption", original, newer).copy(replyToMessageIdHex = "reply")
            val engine = DraftUploadEngine(updated).apply { refuseDraftCleanup = true }
            val initial = acceptanceAppState(engine)
            val journal = AttachmentCollectionJournal(initial.appContext.noBackupFilesDir)
            var beforeAcceptance: AttachmentCollectionOperation? = null
            engine.onAdmission = { beforeAcceptance = journal.read("acct", group.groupIdHex).single() }
            try {
                val controller = ConversationController(appState = initial, initialGroup = group)
                val pending =
                    PendingAttachment(
                        byteArrayOf(),
                        original.mediaType,
                        original.fileName,
                        draftFile = DraftStoredFile(original.id, 900L),
                    )
                controller.admitRetainedMedia("acct", emptyList(), RetainedMediaUpload(listOf(pending), "sent"), "one")
                awaitAttachmentCollectionJournal()
                assertEquals(updated, engine.current)
                assertTrue(journal.read("acct", group.groupIdHex).single().needsStoredDraftCleanup)
                val writeAhead = checkNotNull(beforeAcceptance)
                assertEquals("one", writeAhead.chunks.single().clientToken)
                assertEquals(
                    original.id,
                    writeAhead.items
                        .single()
                        .storedDraft
                        ?.attachmentId,
                )
                // Keep only the journal state we would have if Android stopped immediately after native acceptance.
                assertTrue(journal.write(writeAhead))
                engine.refuseDraftCleanup = false
                val reopened = acceptanceAppState(engine)
                try {
                    reopened.restoreCollectionOperations("acct", group.groupIdHex, readable = { false })
                    awaitAttachmentCollectionJournal()
                    assertEquals(updated.copy(mediaAttachments = listOf(newer)), engine.current)
                    assertTrue(journal.read("acct", group.groupIdHex).isEmpty())
                } finally {
                    reopened.mutationsScope.cancel()
                }
            } finally {
                journal.delete("acct", group.groupIdHex, "stored-one")
                initial.mutationsScope.cancel()
            }
        }

    /** An unchanged stored-file send can consume its caption and file in the same native admission. */
    @Test
    fun exactStoredSelectionConsumesItsCapturedRevision() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val original = draftAttachment("stored-file")
            val engine = DraftUploadEngine(content("sent caption", original))
            val app = acceptanceAppState(engine)
            try {
                val controller = ConversationController(appState = app, initialGroup = group)
                val pending =
                    PendingAttachment(
                        byteArrayOf(),
                        original.mediaType,
                        original.fileName,
                        draftFile = DraftStoredFile(original.id, 900L),
                    )
                val revision = engine.revision
                val retained =
                    RetainedMediaUpload(listOf(pending), "sent caption").apply {
                        draftHandling = RetainedDraftHandling.COMPOSER_SELECTION
                        draftUploadRevision = revision
                        draftSendClearToken = app.captureDraftForSend("acct", group.groupIdHex)
                    }

                controller.admitRetainedMedia("acct", emptyList(), retained, "exact")
                awaitAttachmentCollectionJournal()

                assertSame(revision, engine.admittedConsuming)
                assertEquals("sent caption", engine.admittedCaption)
                assertNull(engine.current)
                assertFalse(engine.calls.contains("clear"))
            } finally {
                app.mutationsScope.cancel()
            }
        }

    /** A fully matching inline photo and caption are consumed by native acceptance, even before host cleanup. */
    @Test
    fun exactInlineSelectionConsumesItsCaptionAtAdmission() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val photo = draftAttachment("photo").copy(fileBacked = false, plaintextSize = 3uL)
            val bytes = byteArrayOf(1, 2, 3)
            val engine = DraftUploadEngine(content("sent caption", photo), fileBody = bytes)
            val revision = engine.revision
            val app = acceptanceAppState(engine)
            try {
                val controller = ConversationController(appState = app, initialGroup = group)
                val item = PendingAttachment(bytes, photo.mediaType, photo.fileName, nativeDraftAttachmentId = photo.id)
                val retained =
                    RetainedMediaUpload(listOf(item), "sent caption").apply {
                        draftHandling = RetainedDraftHandling.COMPOSER_SELECTION
                        draftSendClearToken = app.captureDraftForSend("acct", group.groupIdHex)
                    }

                controller.admitRetainedMedia("acct", emptyList(), retained, "inline-exact")
                awaitAttachmentCollectionJournal()

                assertSame(revision, engine.admittedConsuming)
                assertEquals("sent caption", engine.admittedCaption)
                assertNull(engine.current)
            } finally {
                app.mutationsScope.cancel()
            }
        }

    /** The accepted inline pick disappears while the unsurfaced stored file stays in the native draft. */
    @Test
    fun acceptedInlinePickKeepsHiddenStoredDraftAttachment() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val hidden = draftAttachment("hidden")
            val visible = hidden.copy(id = "photo", fileBacked = false, plaintextSize = 3uL)
            val draft = content("newer caption", visible, hidden).copy(replyToMessageIdHex = "new-reply")
            val engine = DraftUploadEngine(draft, fileBody = byteArrayOf(1, 2, 3))
            val app = acceptanceAppState(engine)
            try {
                val controller = ConversationController(appState = app, initialGroup = group)
                val rendered =
                    PendingAttachment(
                        byteArrayOf(1, 2, 3),
                        visible.mediaType,
                        visible.fileName,
                        nativeDraftAttachmentId = visible.id,
                    )
                val retained =
                    RetainedMediaUpload(listOf(rendered), "sent caption").apply {
                        draftHandling = RetainedDraftHandling.COMPOSER_SELECTION
                    }

                controller.admitRetainedMedia("acct", emptyList(), retained, "inline")
                awaitAttachmentCollectionJournal()

                assertEquals("sent caption", engine.admittedCaption)
                assertNull(engine.admittedConsuming)
                assertEquals(draft.copy(mediaAttachments = listOf(hidden)), engine.current)
            } finally {
                app.mutationsScope.cancel()
            }
        }

    /** A later same-size photo edit reusing the native ID survives accepted-ID reconciliation. */
    @Test
    fun acceptedInlinePickKeepsAnEditedBodyAtTheSameId() =
        runBlocking {
            val group = personTestGroup("group", "Team", members = null).group
            val photo = draftAttachment("photo").copy(fileBacked = false, plaintextSize = 3uL)
            val bytes = byteArrayOf(1, 2, 3)
            val draft = content("sent caption", photo)
            val engine = DraftUploadEngine(draft, fileBody = bytes)
            engine.onAdmission = {
                engine.inlineBody = byteArrayOf(6, 5, 4)
                engine.saveText("next caption")
            }
            val app = acceptanceAppState(engine)
            try {
                val controller = ConversationController(appState = app, initialGroup = group)
                val item = PendingAttachment(bytes, photo.mediaType, photo.fileName, nativeDraftAttachmentId = photo.id)
                val retained =
                    RetainedMediaUpload(listOf(item), "sent caption").apply {
                        draftHandling = RetainedDraftHandling.COMPOSER_SELECTION
                        draftSendClearToken = app.captureDraftForSend("acct", group.groupIdHex)
                    }

                controller.admitRetainedMedia("acct", emptyList(), retained, "edited-photo")
                awaitAttachmentCollectionJournal()

                assertEquals(draft.copy(content = "next caption"), engine.current)
                assertFalse(engine.calls.contains("clear"))
            } finally {
                app.mutationsScope.cancel()
            }
        }

    /** A native stored-file descriptor that can be reselected under a different identity. */
    private fun draftAttachment(id: String) =
        SelectedMessageDraftAttachmentFfi(
            id,
            "same.bin",
            "application/octet-stream",
            900uL,
            null,
            null,
            null,
            emptyList(),
            true,
        )

    /** Provides the test engine through the real app state and its coordinated draft shelf. */
    private fun acceptanceAppState(engine: DraftUploadEngine): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runtime = AppMarmotRuntime("test", engine.marmot)
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "acct",
            initialMarmotRuntime = runtime,
            marmotRuntimeFactory = { runtime },
        )
    }
}

/** Draft content holding [attachments] under [text]. */
private fun content(
    text: String,
    vararg attachments: SelectedMessageDraftAttachmentFfi,
) = SelectedMessageDraftContentFfi("group", text, null, attachments.toList(), 1L, 1L)

/**
 * Answers the draft calls a stored-file send makes like MarmotKit's revisioned draft: each save moves the draft to a
 * new revision, a save under an older one conflicts, and an admission consumes the draft only while it is unchanged.
 */
private class DraftUploadEngine(
    draft: SelectedMessageDraftContentFfi?,
    private val recovered: Boolean = false,
    private val fileBody: ByteArray = ByteArray(900),
) {
    var revision: MessageDraftRevisionFfi = newRevision()
        private set
    var current = draft
        private set
    val calls = mutableListOf<String>()
    var beforeSave: () -> Unit = {}
    var refuseDraftCleanup: Boolean = false
    var onAdmission: () -> Unit = {}
    var beforeFullRead: () -> Unit = {}
    var inlineBody: ByteArray = fileBody
    var savedInputs: List<MessageDraftAttachmentInputFfi>? = null
    var uploadRevision: MessageDraftRevisionFfi? = null
    var uploadedIds: List<String> = emptyList()
    var uploadedRequest: MediaFileUploadRequestFfi? = null
    var admittedCaption: String? = null
    var admittedToken: String? = null
    var admittedConsuming: MessageDraftRevisionFfi? = null

    val marmot: MarmotInterface = Engine()

    /** Saves [text] over the draft and keeps its attachments, as the composer's text save does. */
    fun saveText(text: String) {
        current = current?.copy(content = text)
        revision = newRevision()
    }

    /** Appends [attachment] to the draft, as staging another pick does. */
    fun addAttachment(attachment: SelectedMessageDraftAttachmentFfi) {
        current = current?.let { it.copy(mediaAttachments = it.mediaAttachments + attachment) }
        revision = newRevision()
    }

    /** Refuses a write under any revision but the current one. */
    private fun requireCurrent(written: MessageDraftRevisionFfi) {
        if (written !== revision) throw MarmotKitException.MessageDraftRevisionConflict()
    }

    /** Overrides answer directly, so MarmotKit's exceptions reach the code under test unwrapped. */
    private inner class Engine : MarmotInterface by unexpectedCalls() {
        /** Reports a completed native send when the test recovers one. */
        override fun localSendStatus(
            accountRef: String,
            groupIdHex: String,
            clientToken: String,
        ): LocalSendStatusFfi? {
            calls += "localSendStatus"
            return if (recovered || admittedToken == clientToken) LocalSendStatusFfi.Completed(summary()) else null
        }

        /** Returns the draft under its current revision. */
        override fun selectedMessageDraft(
            accountRef: String,
            groupIdHex: String,
        ): SelectedMessageDraftFfi {
            calls += "selectedMessageDraft"
            return SelectedMessageDraftFfi(revision, current)
        }

        /** Applies retained and removed inputs under the current revision and moves the draft on. */
        override fun saveMessageDraftInputsIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            content: String,
            replyToMessageIdHex: String?,
            mediaAttachments: List<MessageDraftAttachmentInputFfi>,
        ): SelectedMessageDraftFfi {
            calls += "save"
            if (refuseDraftCleanup) throw MarmotKitException.MessageDraftRevisionConflict()
            beforeSave().also { beforeSave = {} }
            requireCurrent(revision)
            savedInputs = mediaAttachments
            val removed = mediaAttachments.filterIsInstance<MessageDraftAttachmentInputFfi.Removed>().map { it.id }
            current =
                current?.let { draft ->
                    draft.copy(
                        content = content,
                        replyToMessageIdHex = replyToMessageIdHex,
                        mediaAttachments = draft.mediaAttachments.filterNot { it.id in removed },
                    )
                }
            this@DraftUploadEngine.revision = newRevision()
            return SelectedMessageDraftFfi(this@DraftUploadEngine.revision, current)
        }

        /** Clears the draft under the current revision. */
        override fun clearMessageDraftIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
        ): SelectedMessageDraftFfi {
            calls += "clear"
            requireCurrent(revision)
            current = null
            this@DraftUploadEngine.revision = newRevision()
            return SelectedMessageDraftFfi(this@DraftUploadEngine.revision, null)
        }

        /** Returns inline bytes under exactly the selected opaque revision. */
        override fun messageDraftAttachmentIfRevision(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachmentId: String,
        ): ByteArray? {
            requireCurrent(revision)
            return inlineBody.takeIf { current?.mediaAttachments?.any { row -> row.id == attachmentId } == true }
        }

        /** Checks that the privacy read uses exactly the selected revision that will be uploaded. */
        override suspend fun readMessageDraftAttachment(
            accountRef: String,
            revision: MessageDraftRevisionFfi,
            attachmentId: String,
            offset: ULong,
            limit: UInt,
        ): ByteArray {
            calls += "read"
            if (limit > 1_024u) beforeFullRead()
            requireCurrent(revision)
            val start = offset.toInt().coerceAtMost(fileBody.size)
            return fileBody.copyOfRange(start, minOf(fileBody.size, start + limit.toInt()))
        }

        /** Records the ids and revision an upload from the draft names. */
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

        /** Records a file upload request. */
        override suspend fun uploadMediaFiles(
            accountRef: String,
            groupIdHex: String,
            request: MediaFileUploadRequestFfi,
            control: MediaFileTransferControlFfi,
        ): MediaUploadResultFfi {
            calls += "uploadFiles"
            uploadedRequest = request
            return MediaUploadResultFfi(emptyList(), null)
        }

        /** Admits the snapshot and consumes the draft only while [consumingDraft] is still its revision. */
        override suspend fun sendPreparedMediaWithClientToken(
            accountRef: String,
            groupIdHex: String,
            attachments: List<MediaAttachmentReferenceFfi>,
            caption: String?,
            clientToken: String,
            consumingDraft: MessageDraftRevisionFfi?,
        ): LocalSendAcceptanceFfi {
            calls += "admit"
            admittedCaption = caption
            onAdmission()
            admittedToken = clientToken
            admittedConsuming = consumingDraft
            if (consumingDraft != null && consumingDraft === revision) {
                current = null
                revision = newRevision()
            }
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

/** A fresh draft revision handle that needs no native library. */
private fun newRevision(): MessageDraftRevisionFfi = nativeHandleStub(MessageDraftRevisionFfi::class.java)

/** Fails any binding call a stored-file upload test did not expect. */
private fun unexpectedCalls(): MarmotInterface =
    nativeBoundary { method, _ ->
        error("unexpected native call $method")
    }
