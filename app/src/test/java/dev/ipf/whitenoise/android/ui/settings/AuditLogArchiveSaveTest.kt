package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Saving to a user-selected document must either land the whole archive or leave nothing that
 * looks like a complete export behind.
 */
class AuditLogArchiveSaveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** A successful save streams the archive through to the selected document unchanged. */
    @Test
    fun saveWritesTheWholeArchiveToTheSelectedDocument() =
        runBlocking {
            val bytes = ByteArray(BYTES_LARGER_THAN_ONE_BUFFER) { (it % BYTE_RANGE).toByte() }
            val archive = temporaryFolder.newFile("export.zip").apply { writeBytes(bytes) }
            val destination = ByteArrayOutputStream()
            var discarded = false

            saveAuditLogArchive(archive, openOutput = { destination }, discardOutput = { discarded = true })

            assertArrayEquals(bytes, destination.toByteArray())
            assertFalse(discarded)
        }

    /** A provider that refuses to open a stream fails the save and discards the destination. */
    @Test
    fun saveDiscardsWhenTheProviderOpensNoStream() =
        runBlocking {
            val archive = temporaryFolder.newFile("export.zip").apply { writeText("body") }
            var discarded = false

            val failure =
                runCatching {
                    saveAuditLogArchive(archive, openOutput = { null }, discardOutput = { discarded = true })
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(discarded)
        }

    /** A write that fails part-way discards the incomplete document rather than leaving it. */
    @Test
    fun saveDiscardsAnIncompleteDocumentWhenWritingFails() =
        runBlocking {
            val archive =
                temporaryFolder.newFile("export.zip").apply {
                    writeBytes(ByteArray(BYTES_LARGER_THAN_ONE_BUFFER))
                }
            var discarded = false
            val failing =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("device full")

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = throw IOException("device full")
                }

            val failure =
                runCatching {
                    saveAuditLogArchive(archive, openOutput = { failing }, discardOutput = { discarded = true })
                }.exceptionOrNull()

            assertEquals("device full", failure?.message)
            assertTrue(discarded)
        }

    /**
     * A cleanup that itself fails never hides why the save failed: the caller still sees the save's
     * own failure, so the message shown to the reader describes the export rather than the cleanup.
     *
     * The failure is asserted by type and message rather than by suppressed exceptions, because
     * coroutine stack-trace recovery rebuilds an exception crossing `withContext` and does not
     * carry suppressed entries across that copy.
     */
    @Test
    fun saveReportsTheSaveFailureEvenWhenCleanupAlsoFails() =
        runBlocking {
            val archive = temporaryFolder.newFile("export.zip").apply { writeText("body") }
            var attemptedCleanup = false

            val failure =
                runCatching {
                    saveAuditLogArchive(
                        archive,
                        openOutput = { null },
                        discardOutput = {
                            attemptedCleanup = true
                            throw IllegalStateException("cleanup failed")
                        },
                    )
                }.exceptionOrNull()

            assertTrue(attemptedCleanup)
            assertTrue(failure is IOException)
            assertEquals("The document provider did not open an output stream", failure?.message)
        }

    private companion object {
        /** Larger than one copy buffer, so the streaming path is exercised rather than a single write. */
        const val BYTES_LARGER_THAN_ONE_BUFFER = 40_000
        const val BYTE_RANGE = 251
    }
}
