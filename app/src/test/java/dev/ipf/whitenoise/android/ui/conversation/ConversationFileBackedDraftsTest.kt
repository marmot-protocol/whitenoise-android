package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.FileBackedDraftNative
import dev.ipf.whitenoise.android.media.editor.InMemoryEditorStrings
import dev.ipf.whitenoise.android.media.editor.MarmotMessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.RevisionedMessageDraft
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.media.editor.nativeHandleStub
import dev.ipf.whitenoise.android.state.DraftStoredFile
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedVideoMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins how a pick too large for memory enters the draft as a stored file and comes back out of it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationFileBackedDraftsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A file-read pick is stored under its stable id with its metadata, and its private copy is deleted. */
    @Test
    fun filePickIsStoredAndItsPrivateCopyDeleted() =
        runTest {
            val native = FileBackedDraftNative("caption")
            val source = privateCopy(bytes = 4096)

            val prepared = stageFilePick(repositoryFor(native), "acct", pending(source))

            val request = native.stagedRequests.single()
            assertEquals("slot-id", request.id)
            assertEquals(source.file.absolutePath, request.sourcePath)
            assertEquals(4096uL, request.expectedSize)
            assertEquals("clip.mp4", request.fileName)
            assertEquals("slot-id", prepared!!.attachment.id)
            assertFalse(prepared.attachment.hasInlineBody())
            assertEquals(4096L, prepared.storedBytes)
            assertFalse(source.file.exists())
        }

    /** A refused copy (no room on the device here) leaves no attachment and still deletes the private copy. */
    @Test
    fun refusedFileLeavesNothingBehind() =
        runTest {
            val native = FileBackedDraftNative("caption")
            native.stageFailure = MarmotKitException.StorageFull("no room")
            val source = privateCopy(bytes = 4096)

            val prepared = stageFilePick(repositoryFor(native), "acct", pending(source))

            assertNull(prepared)
            assertTrue(native.attachmentIds.isEmpty())
            assertFalse(source.file.exists())
        }

    /** A stored entry with a known size reaches a send as a reference to the draft's file, never as empty bytes. */
    @Test
    fun storedEntryReachesASendAsADraftFile() {
        val stored = DraftPreparedPhoto(bodyless("clip"), "digest", storedBytes = 900L)
        val unsized = DraftPreparedPhoto(bodyless("other"), "digest")

        val sendable = mapOf("clip" to stored, "other" to unsized).sendableBodies()

        val attachment = sendable.getValue("clip")
        assertEquals(DraftStoredFile("clip", 900L), attachment.draftFile)
        assertEquals(900L, attachment.byteCount)
        assertTrue(attachment.isFileBacked)
        assertNull(attachment.inMemoryBytes)
        assertEquals(setOf("clip"), sendable.keys)
        assertTrue(sendable.withoutStoredFiles().isEmpty())
    }

    /** A restored body-less entry takes the stored size from the read it was restored from; inline ones keep none. */
    @Test
    fun restoredEntriesTakeTheirStoredSize() {
        val read =
            RevisionedMessageDraft(
                revision = null,
                draft = null,
                fileBacked =
                    mapOf(
                        "clip" to
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
                            ),
                    ),
            )
        val restored =
            mapOf(
                "clip" to DraftPreparedPhoto(bodyless("clip"), "digest", restoredFromNative = true),
                "photo" to DraftPreparedPhoto(bodyless("photo").copy(plaintext = byteArrayOf(1)), "digest"),
            ).withStoredSizes(read)

        assertEquals(900L, restored.getValue("clip").storedBytes)
        assertEquals(0L, restored.getValue("photo").storedBytes)
    }

    /** The pre-send metadata re-check keeps a stored video as stored: it was cleaned before the draft took it. */
    @Test
    fun storedVideoPassesThePreSendMetadataCheck() {
        val stored = PendingAttachment(ByteArray(0), "video/mp4", "clip.mp4", draftFile = DraftStoredFile("clip", 900L))

        val checked = removePreparedVideoMetadata(mapOf("clip" to stored))

        assertEquals(mapOf("clip" to stored), checked.ready)
        assertTrue(checked.refused.isEmpty())
    }

    /** A stored body is copied chunk by chunk until the empty end chunk. */
    @Test
    fun storedBodyCopiesUntilTheEndChunk() =
        runTest {
            val chunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4), ByteArray(0))
            val offsets = mutableListOf<Long>()
            val target = folder.newFile("restored")

            val written =
                copyDraftFileBody(target) { offset ->
                    offsets += offset
                    chunks[offsets.size - 1]
                }

            assertEquals(4L, written)
            assertEquals(listOf(0L, 3L, 4L), offsets)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
        }

    /** A body that disappears mid-copy reports -1 so the restore drops the partial file. */
    @Test
    fun vanishedBodyReportsGone() =
        runTest {
            val target = folder.newFile("restored")
            var reads = 0

            val written = copyDraftFileBody(target) { if (reads++ == 0) byteArrayOf(1) else null }

            assertEquals(-1L, written)
        }

    /** A body-less draft entry for [id], as a stored file reads. */
    private fun bodyless(id: String) =
        MessageDraftAttachmentFfi(
            id = id,
            fileName = "$id.mp4",
            mediaType = "video/mp4",
            plaintext = ByteArray(0),
            dim = null,
            thumbhash = null,
            durationSeconds = null,
            waveformSamples = emptyList(),
        )

    /** A repository over [native] whose I/O runs on the test scheduler. */
    private fun TestScope.repositoryFor(native: FileBackedDraftNative) =
        MessageDraftRepository(
            gateway = MarmotMessageDraftGateway { native.marmot },
            editorSessions = EditorSessionStore(InMemoryEditorStrings()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    /** A private copy of [bytes] zero bytes, as the reader would make. */
    private fun privateCopy(bytes: Int): StagedUploadSource {
        val file = folder.newFile().apply { writeBytes(ByteArray(bytes)) }
        return StagedUploadSource(file, bytes.toLong())
    }

    /** A video pick read into [source]. */
    private fun pending(source: StagedUploadSource) =
        PendingAttachment(
            plaintextBytes = ByteArray(0),
            mediaType = "video/mp4",
            fileName = "clip.mp4",
            dim = "1920x1080",
            sourceFile = source,
        )
}

/** Stages [pending] as slot-id through the file path, with a transfer control that needs no native library. */
private suspend fun stageFilePick(
    drafts: MessageDraftRepository,
    accountRef: String,
    pending: PendingAttachment,
) = stageFileBackedAttachment(
    drafts,
    DraftAttachmentTarget(accountRef, "group", "slot-id"),
    pending,
    newControl = { nativeHandleStub(MediaFileTransferControlFfi::class.java) },
)
