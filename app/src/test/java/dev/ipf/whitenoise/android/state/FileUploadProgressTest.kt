package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUploadProgressTest {
    private val file = 1_000L
    private val ciphertext = file + 16L

    /** MDK's private copy counts the file's own bytes, from nothing to the whole file. */
    @Test
    fun theCopyPassIsPreparing() {
        assertEquals(progress(FileUploadPhase.PREPARING, 0L), fileUploadProgress(0L, file)?.withoutFraction())
        assertEquals(progress(FileUploadPhase.PREPARING, 400L), fileUploadProgress(400L, file)?.withoutFraction())
        assertEquals(progress(FileUploadPhase.PREPARING, file), fileUploadProgress(file, file)?.withoutFraction())
    }

    /** Encryption resumes the counter at the file's size and counts its own bytes from there. */
    @Test
    fun theEncryptionPassCountsFromTheFileSize() {
        assertEquals(progress(FileUploadPhase.ENCRYPTING, 1L), fileUploadProgress(file + 1L, file)?.withoutFraction())
        assertEquals(progress(FileUploadPhase.ENCRYPTING, file), fileUploadProgress(2 * file, file)?.withoutFraction())
    }

    /**
     * The upload starts at twice the ciphertext length, so the gap after encryption shows nothing sent,
     * and sent bytes are shown against the file's own size rather than the ciphertext's.
     */
    @Test
    fun theUploadCountsSentBytesAgainstTheFileSize() {
        assertEquals(
            progress(FileUploadPhase.UPLOADING, 0L),
            fileUploadProgress(2 * file + 10L, file)?.withoutFraction(),
        )
        assertEquals(
            progress(FileUploadPhase.UPLOADING, 250L),
            fileUploadProgress(2 * ciphertext + 250L, file)?.withoutFraction(),
        )
        assertEquals(
            "the trailing tag is not shown as extra bytes",
            progress(FileUploadPhase.UPLOADING, file),
            fileUploadProgress(3 * ciphertext - 1L, file)?.withoutFraction(),
        )
    }

    /** Once every ciphertext byte is sent, only the message is left to send. */
    @Test
    fun everyByteSentMeansSending() {
        val done = fileUploadProgress(3 * ciphertext, file)

        assertEquals(FileUploadPhase.SENDING, done?.phase)
        assertEquals(1f, done?.fraction)
    }

    /** The ring's fraction covers the whole send and never falls as the phase changes. */
    @Test
    fun theFractionOnlyGrowsAcrossPhases() {
        val fractions = (0L..3 * ciphertext step 7L).map { requireNotNull(fileUploadProgress(it, file)).fraction }

        assertTrue(fractions.zipWithNext().all { (earlier, later) -> later >= earlier })
        assertEquals(0f, fractions.first())
    }

    /** An empty or unknown file, or a counter that makes no sense, shows no progress at all. */
    @Test
    fun nothingIsShownWithoutAFileOrACounter() {
        assertNull(fileUploadProgress(10L, 0L))
        assertNull(fileUploadProgress(-1L, file))
    }

    /** Builds the expected progress for [phase] with [phaseBytes] handled, ignoring the fraction. */
    private fun progress(
        phase: FileUploadPhase,
        phaseBytes: Long,
    ) = FileUploadProgress(phase, phaseBytes, file, 0f)

    /** The same progress with its fraction cleared, so a test can compare phase and bytes alone. */
    private fun FileUploadProgress.withoutFraction() = copy(fraction = 0f)
}
