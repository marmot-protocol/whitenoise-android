package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class StagedDocumentReadTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun exactBoundIsAcceptedAndSourceIsPrivateAndDeletedAfterUse() {
        val root = temporary.newFolder("staged")
        val result = readStagedDocument(root, 65537) { ByteArrayInputStream(ByteArray(65537) { 7 }) }
        val source = (result as StagedDocumentRead.Success).source
        assertEquals(65537L, source.byteCount)
        assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(source.file.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.toPath())))
        assertEquals(7, source.file.inputStream().use { it.read() })
        source.close()
        assertFalse(source.file.exists())
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun diagnosticsOmitDocumentNamesPlaintextAndPrivatePaths() {
        val root = temporary.newFolder("private-document")
        val result = readStagedDocument(root, 100) { ByteArrayInputStream(byteArrayOf(42, 99)) }
        val source = (result as StagedDocumentRead.Success).source
        val staged = PendingAttachment(byteArrayOf(), "text/plain", "confidential.txt", sourceFile = source)
        val memory = PendingAttachment(byteArrayOf(42, 99), "text/plain", "confidential.txt")
        try {
            assertEquals("StagedUploadSource(byteCount=2)", source.toString())
            assertEquals("PendingAttachment(byteCount=2, fileBacked=true)", staged.toString())
            assertEquals("PendingAttachment(byteCount=2, fileBacked=false)", memory.toString())
        } finally {
            source.close()
        }
    }

    @Test
    fun oversizeStreamStopsAtTheBoundAndRemovesPartial() {
        val root = temporary.newFolder("staged")
        var consumed = 0L
        val stream =
            object : InputStream() {
                override fun read(): Int = 1.also { consumed++ }

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    assertTrue(length <= 64 * 1024)
                    consumed += length
                    return length
                }
            }
        assertEquals(StagedDocumentRead.TooLarge, readStagedDocument(root, 65536) { stream })
        assertEquals(65537L, consumed)
        assertEquals(0, root.listFiles()!!.size)
    }

    /** Provider reads stay bounded even when the allowed byte count cannot fit in an Int. */
    @Test
    fun longSizedBudgetDoesNotOverflowTheReadLength() {
        val root = temporary.newFolder("long-budget")
        var remaining = 2L * 64 * 1024 + 1
        val expected = remaining
        val stream =
            object : InputStream() {
                override fun read(): Int = error("positive-length block reads must suffice")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    assertTrue(length in 1..64 * 1024)
                    if (remaining == 0L) return -1
                    val count = minOf(length.toLong(), remaining).toInt()
                    buffer.fill(7, offset, offset + count)
                    remaining -= count
                    return count
                }
            }
        val read = readStagedDocument(root, Int.MAX_VALUE.toLong() + 1) { stream }
        val source = (read as StagedDocumentRead.Success).source
        try {
            assertEquals(expected, source.byteCount)
            assertEquals(expected, source.file.length())
        } finally {
            source.close()
        }
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun cancellationIsPropagatedAndPartialSourceIsRemoved() {
        val root = temporary.newFolder("staged")
        var checks = 0
        try {
            readStagedDocument(root, 100000, checkCancellation = {
                checks++
                if (checks >= 3) throw CancellationException("fixture")
            }) { ByteArrayInputStream(ByteArray(100000)) }
            error("expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, root.listFiles()!!.size)
        }
    }

    @Test
    fun emptyMissingAndFailedProvidersLeaveNoSource() {
        val root = temporary.newFolder("staged")
        assertEquals(StagedDocumentRead.Empty, readStagedDocument(root, 100) { ByteArrayInputStream(byteArrayOf()) })
        assertEquals(StagedDocumentRead.Unreadable, readStagedDocument(root, 100) { null })
        assertEquals(
            StagedDocumentRead.Unreadable,
            readStagedDocument(root, 100) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("fixture")
                }
            },
        )
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun providerCloseFailureDoesNotLeakACompletedSource() {
        val root = temporary.newFolder("staged")
        assertEquals(
            StagedDocumentRead.Unreadable,
            readStagedDocument(root, 100) {
                object : ByteArrayInputStream(byteArrayOf(1)) {
                    override fun close(): Unit = throw IOException("fixture")
                }
            },
        )
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun aSymlinkStagingDirectoryIsRefused() {
        val target = temporary.newFolder("target")
        val link = temporary.root.toPath().resolve("linked")
        Files.createSymbolicLink(link, target.toPath())
        assertEquals(
            StagedDocumentRead.Unreadable,
            readStagedDocument(link.toFile(), 100) {
                ByteArrayInputStream(byteArrayOf(1))
            },
        )
        assertEquals(0, target.listFiles()!!.size)
    }

    @Test
    fun sourceRemovalWaitsForTheNativeSnapshotLease() {
        val source =
            (
                readStagedDocument(temporary.newFolder("staged"), 10) {
                    ByteArrayInputStream(byteArrayOf(1, 2))
                } as StagedDocumentRead.Success
            ).source
        val lease = source.acquire()
        source.close()
        assertTrue(source.file.exists())
        lease.close()
        lease.close()
        assertFalse(source.file.exists())
    }

    @Test
    fun mixedAlbumPinsExistingSourcesAndRemovesOnlyConvertedArrays() {
        val root = temporary.newFolder("staged")
        val staged = readStagedDocument(root, 10) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        val source = (staged as StagedDocumentRead.Success).source
        val batch =
            stageFileUploadSources(
                listOf(
                    PendingAttachment(byteArrayOf(), "application/octet-stream", "large", sourceFile = source),
                    PendingAttachment(byteArrayOf(3), "text/plain", "small"),
                ),
                root,
                35,
            )
        assertEquals(listOf(2L, 1L), batch.inputs.map { it.byteCount })
        assertEquals(2, root.listFiles()!!.size)
        batch.close()
        assertEquals(1, root.listFiles()!!.size)
        assertTrue(source.file.exists())
        source.close()
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun tagOverheadIsCheckedBeforeAnySourceCopy() {
        val root = temporary.newFolder("staged")
        try {
            stageFileUploadSources(listOf(PendingAttachment(byteArrayOf(1), "text/plain", "small")), root, 16)
            error("expected tag bound refusal")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, root.listFiles()!!.size)
        }
    }
}
