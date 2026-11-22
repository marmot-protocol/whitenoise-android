package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins how Android's draft layer keeps MarmotKit 0.18.0's stored files across every kind of draft write. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBackedMessageDraftTest {
    /** A stored file reads as a body-less entry listed as file-backed; inline bodies are read under that revision. */
    @Test
    fun readKeepsStoredFilesBodyless() {
        val native = FileBackedDraftNative("caption")
        native.holdInline("photo", byteArrayOf(1, 2))
        native.holdFile("video", 600_000_000uL)

        val read = MarmotMessageDraftGateway { native.marmot }.readRevisioned("acct", "group")

        val attachments = read.draft!!.mediaAttachments
        assertEquals(listOf("photo", "video"), attachments.map { it.id })
        assertArrayEquals(byteArrayOf(1, 2), attachments[0].plaintext)
        assertFalse(attachments[1].hasInlineBody())
        assertEquals(setOf("video"), read.fileBacked.keys)
        assertEquals(600_000_000uL, read.fileBacked.getValue("video").plaintextSize)
        assertFalse("messageDraft" in native.calls)
    }

    /** A save keeps a stored file and an untouched inline attachment by id and writes only new bytes inline. */
    @Test
    fun saveKeepsHeldAttachmentsById() {
        val native = FileBackedDraftNative()
        native.holdInline("photo", byteArrayOf(1))
        native.holdFile("video", 900uL)
        val gateway = MarmotMessageDraftGateway { native.marmot }
        val base = gateway.readRevisioned("acct", "group")
        val added = inline("doc", byteArrayOf(7))

        gateway.saveRevisioned(
            "acct",
            "group",
            base,
            MessageDraftWrite("text", null, base.draft!!.mediaAttachments + added),
        )

        assertEquals(
            listOf(
                MessageDraftAttachmentInputFfi.Retained("photo"),
                MessageDraftAttachmentInputFfi.Retained("video"),
                MessageDraftAttachmentInputFfi.Inline(added),
            ),
            native.savedInputs.single(),
        )
        assertEquals(listOf("photo", "video", "doc"), native.attachmentIds)
    }

    /** A body-less attachment the draft does not hold as a file is refused rather than saved empty. */
    @Test
    fun saveRefusesBodylessAttachmentThatIsNotAHeldFile() {
        val native = FileBackedDraftNative()
        val gateway = MarmotMessageDraftGateway { native.marmot }
        val base = gateway.readRevisioned("acct", "group")

        val refused =
            runCatching {
                val bodyless = listOf(inline("x", ByteArray(0)))
                gateway.saveRevisioned("acct", "group", base, MessageDraftWrite("", null, bodyless))
            }.exceptionOrNull()

        assertTrue(refused is IllegalStateException)
        assertTrue(native.savedInputs.isEmpty())
    }

    /** An inline body that vanished during a read is a revision conflict, so a mutation re-reads. */
    @Test
    fun vanishedInlineBodyIsARevisionConflict() {
        val native = FileBackedDraftNative()
        native.holdInline("photo", byteArrayOf(1))
        native.dropInlineBody("photo")

        val failure = runCatching { MarmotMessageDraftGateway { native.marmot }.readRevisioned("acct", "group") }

        assertTrue(failure.exceptionOrNull() is MarmotKitException.MessageDraftRevisionConflict)
    }

    /** Text saves keep a stored file, and clearing the text of a draft that holds only a file does not delete it. */
    @Test
    fun textSaveKeepsStoredFile() =
        runTest {
            val native = FileBackedDraftNative("hello")
            native.holdFile("video", 900uL)
            val repository = repositoryFor(native)

            assertTrue(repository.saveText("acct", "group", "hello there") is MessageDraftMutationResult.Success)
            assertTrue(repository.saveText("acct", "group", "") is MessageDraftMutationResult.Success)

            assertEquals(listOf("video"), native.attachmentIds)
            assertEquals("", native.content)
            assertEquals(List(2) { listOf(MessageDraftAttachmentInputFfi.Retained("video")) }, native.savedInputs)
            assertFalse("clearMessageDraftIfRevision" in native.calls)
        }

    /** Removing another attachment keeps the stored file; removing the stored file leaves it out of the save. */
    @Test
    fun removalKeepsOrDropsStoredFile() =
        runTest {
            val native = FileBackedDraftNative("caption")
            native.holdInline("photo", byteArrayOf(1))
            native.holdFile("video", 900uL)
            val repository = repositoryFor(native)

            repository.removeAttachment("acct", "group", "photo")
            assertEquals(listOf("video"), native.attachmentIds)

            repository.removeAttachment("acct", "group", "video")
            assertEquals(emptyList<String>(), native.attachmentIds)
            assertEquals(emptyList<MessageDraftAttachmentInputFfi>(), native.savedInputs.last())
        }

    /** Staging copies a file once into the selected revision and returns the draft with its body-less entry. */
    @Test
    fun stagingAddsOneStoredFile() =
        runTest {
            val native = FileBackedDraftNative("caption")
            val repository = repositoryFor(native)

            val result = repository.files.add("acct", "group", request("video"), control())

            val draft = (result as MessageDraftMutationResult.Success).draft!!
            assertEquals(listOf("video"), draft.mediaAttachments.map { it.id })
            assertFalse(draft.mediaAttachments.single().hasInlineBody())
            assertEquals(1, native.stagedRequests.size)
        }

    /** A held id is a duplicate without copying, and a lost revision is reported, never copied again. */
    @Test
    fun stagingIsNeverRetried() =
        runTest {
            val held = FileBackedDraftNative()
            held.holdFile("video", 900uL)
            val duplicate = repositoryFor(held).files.add("acct", "group", request("video"), control())
            assertEquals(MessageDraftMutationResult.DuplicateAttachment, duplicate)
            assertTrue(held.stagedRequests.isEmpty())

            val moved = FileBackedDraftNative()
            moved.stageFailure = MarmotKitException.MessageDraftRevisionConflict()
            val conflict = repositoryFor(moved).files.add("acct", "group", request("video"), control())
            assertTrue(conflict.isRevisionConflict())
            assertEquals(1, moved.stagedRequests.size)
        }

    /** A repository over [native] whose I/O runs on the test scheduler. */
    private fun TestScope.repositoryFor(native: FileBackedDraftNative) =
        MessageDraftRepository(
            gateway = MarmotMessageDraftGateway { native.marmot },
            editorSessions = EditorSessionStore(InMemoryEditorStrings()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    /** An inline attachment with [body]. */
    private fun inline(
        id: String,
        body: ByteArray,
    ) = MessageDraftAttachmentFfi(id, "$id.bin", "application/octet-stream", body, null, null, null, emptyList())

    /** A file request for [id]. */
    private fun request(id: String) =
        MessageDraftAttachmentFileRequestFfi(
            id,
            "/private/$id",
            900uL,
            "$id.mp4",
            "video/mp4",
            null,
            null,
            null,
            emptyList(),
        )

    /** A transfer control that needs no native library. */
    private fun control() = nativeHandleStub(MediaFileTransferControlFfi::class.java)
}
