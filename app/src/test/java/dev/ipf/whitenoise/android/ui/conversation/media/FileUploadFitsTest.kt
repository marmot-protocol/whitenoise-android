package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.PendingAttachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A forward uses MDK's file path only for a message one native file batch can take with the disk it needs, and keeps
 * the in-memory upload otherwise, so the switch to file uploads never fails a forward the old path could send (#2556).
 */
class FileUploadFitsTest {
    /** A small message within the batch and with room on disk goes through the file path. */
    @Test
    fun aMessageWithinTheBatchAndDiskFits() {
        assertTrue(fileUploadFits(listOf(item(BYTES)), BATCH, ROOMY_DISK))
    }

    /** More items than one native file batch accepts keep the in-memory upload. */
    @Test
    fun tooManyItemsDoNotFit() {
        assertTrue(fileUploadFits(List(MAX_ITEMS) { item(1) }, BATCH, ROOMY_DISK))
        assertFalse(fileUploadFits(List(MAX_ITEMS + 1) { item(1) }, BATCH, ROOMY_DISK))
    }

    /** Ciphertext beyond the native batch bound, counting one tag per item, keeps the in-memory upload. */
    @Test
    fun ciphertextBeyondTheBatchBoundDoesNotFit() {
        assertTrue(fileUploadFits(listOf(item(BYTES)), BYTES + TAG_BYTES, ROOMY_DISK))
        assertFalse(fileUploadFits(listOf(item(BYTES)), BYTES + TAG_BYTES - 1, ROOMY_DISK))
    }

    /** Too little free disk for the staged copies and the app's reserve keeps the in-memory upload. */
    @Test
    fun tooLittleDiskDoesNotFit() {
        val needed = BYTES * FILE_BACKED_DISK_COPIES + FILE_BACKED_DISK_RESERVE_BYTES

        assertTrue(fileUploadFits(listOf(item(BYTES)), BATCH, needed))
        assertFalse(fileUploadFits(listOf(item(BYTES)), BATCH, needed - 1))
    }

    /** An empty message or an empty item never takes the file path. */
    @Test
    fun emptyInputsDoNotFit() {
        assertFalse(fileUploadFits(emptyList(), BATCH, ROOMY_DISK))
        assertFalse(fileUploadFits(listOf(item(0)), BATCH, ROOMY_DISK))
    }

    /** An in-memory attachment of [bytes] plaintext bytes. */
    private fun item(bytes: Int) = PendingAttachment(ByteArray(bytes), "text/plain", "notes.txt")

    private companion object {
        const val BYTES = 1_024
        const val TAG_BYTES = 16L
        const val MAX_ITEMS = 64
        const val BATCH = 64L * 1024L * 1024L
        const val ROOMY_DISK = Long.MAX_VALUE / 4
    }
}
