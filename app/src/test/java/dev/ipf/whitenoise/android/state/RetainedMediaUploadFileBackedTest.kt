package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.conversation.media.StagedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.readStagedDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class RetainedMediaUploadFileBackedTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** Only an in-memory attachment exposes bytes; a file-backed or empty one reads as absent, never as content. */
    @Test
    fun inMemoryBytesAreAbsentForFileBackedAndEmptyAttachments() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = stagedSource(bytes)
        try {
            assertArrayEquals(bytes, PendingAttachment(bytes, "text/plain", "a.txt").inMemoryBytes)
            assertNull(PendingAttachment(ByteArray(0), "text/plain", "a.txt").inMemoryBytes)
            val fileBacked = PendingAttachment(ByteArray(0), "text/plain", "a.txt", sourceFile = source)
            assertNull(fileBacked.inMemoryBytes)
            assertEquals(3L, fileBacked.byteCount)
            assertTrue(fileBacked.hasContent)
        } finally {
            source.close()
        }
    }

    /**
     * Ending a retained upload releases its intake lease once and hands each staged snapshot to the closer
     * every time; closing a snapshot twice is harmless.
     */
    @Test
    fun releaseHandsStagedSnapshotsToTheCloserAfterTheIntakeRelease() {
        val first = stagedSource(byteArrayOf(1))
        val second = stagedSource(byteArrayOf(2))
        val upload =
            RetainedMediaUpload(
                attachments =
                    listOf(
                        PendingAttachment(ByteArray(0), "video/mp4", "a.mp4", sourceFile = first),
                        PendingAttachment(byteArrayOf(9), "image/jpeg", "b.jpg"),
                        PendingAttachment(ByteArray(0), "application/pdf", "c.pdf", sourceFile = second),
                    ),
                caption = null,
            )
        val events = mutableListOf<String>()
        upload.retainSource { events += "lease" }

        upload.releaseSource { source ->
            events += "close"
            source.close()
        }
        upload.releaseSource { events += "second close" }

        assertEquals(listOf("lease", "close", "close", "second close", "second close"), events)
        assertFalse(first.file.exists())
        assertFalse(second.file.exists())
    }

    /**
     * A Cancel pressed while the send still waits for the commit lock is kept and stops the transfer the
     * moment it registers; a later press reaches a running transfer, and the request is reported once.
     */
    @Test
    fun transferCancelIsKeptUntilTheTransferRegistersAndReportedOnce() {
        val upload = RetainedMediaUpload(attachments = emptyList(), caption = null)
        var cancels = 0

        upload.requestTransferCancel()
        assertEquals("nothing is running yet", 0, cancels)
        upload.attachTransferCancel { cancels += 1 }
        assertEquals("the waiting send is stopped as its transfer registers", 1, cancels)
        upload.requestTransferCancel()
        assertEquals("a second press reaches the running transfer", 2, cancels)
        upload.attachTransferCancel(null)
        upload.requestTransferCancel()
        assertEquals("a returned transfer is not called again", 2, cancels)

        assertTrue(upload.consumeTransferCancelRequest())
        assertFalse(upload.consumeTransferCancelRequest())
        upload.attachTransferCancel { cancels += 1 }
        assertEquals("a consumed request does not stop a later transfer", 2, cancels)
    }

    /**
     * A send of one staged file follows MDK's counter, never moves backwards on a late lower reading,
     * and forgets its bytes when the attempt fails.
     */
    @Test
    fun aSingleStagedFileReportsProgressUntilItsAttemptIsCleared() {
        val source = stagedSource(ByteArray(100))
        try {
            val upload =
                RetainedMediaUpload(
                    attachments =
                        listOf(PendingAttachment(ByteArray(0), "application/pdf", "big.pdf", sourceFile = source)),
                    caption = null,
                )
            assertNull(upload.uploadProgress.value)

            upload.reportTransferProgress(150L)
            assertEquals(FileUploadPhase.ENCRYPTING, upload.uploadProgress.value?.phase)
            assertEquals(50L, upload.uploadProgress.value?.phaseBytes)

            upload.reportTransferProgress(120L)
            assertEquals("a lower reading never moves the ring back", 50L, upload.uploadProgress.value?.phaseBytes)

            upload.clearTransferProgress()
            assertNull(upload.uploadProgress.value)
        } finally {
            source.close()
        }
    }

    /** MDK's counter is per item, so an album, or a send held in memory, never shows byte progress. */
    @Test
    fun albumsAndInMemorySendsShowNoProgress() {
        val source = stagedSource(ByteArray(100))
        try {
            val album =
                RetainedMediaUpload(
                    attachments =
                        listOf(
                            PendingAttachment(ByteArray(0), "video/mp4", "a.mp4", sourceFile = source),
                            PendingAttachment(byteArrayOf(9), "image/jpeg", "b.jpg"),
                        ),
                    caption = null,
                )
            val inMemory =
                RetainedMediaUpload(listOf(PendingAttachment(byteArrayOf(1, 2), "text/plain", "a.txt")), null)

            album.reportTransferProgress(50L)
            inMemory.reportTransferProgress(1L)

            assertNull(album.uploadProgress.value)
            assertNull(inMemory.uploadProgress.value)
        } finally {
            source.close()
        }
    }

    /** Stages [bytes] into a private snapshot under the test directory. */
    private fun stagedSource(bytes: ByteArray): StagedUploadSource {
        val read = readStagedDocument(temporary.root, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        return (read as StagedDocumentRead.Success).source
    }
}
