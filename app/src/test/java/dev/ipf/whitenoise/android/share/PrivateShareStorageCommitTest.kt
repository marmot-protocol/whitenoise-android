package dev.ipf.whitenoise.android.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Real filesystem replacement faults cannot be acknowledged as a new private ownership record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateShareStorageCommitTest {
    /** Denies the rename after the new stream exists while leaving the older base readable. */
    @Test
    fun rejectedReplacementKeepsTheOlderLeaseAndReportsFailure() {
        val root = Files.createTempDirectory("lease-replacement").toFile()
        val base = File(root, "shelf.lease")
        val older = JSONObject().put("uris", org.json.JSONArray(listOf("content://private/older")))
        writePrivateShareJson(base, older)
        var renameReached = false
        val deniedReplacement =
            object : File(root, base.name) {
                override fun isDirectory(): Boolean {
                    renameReached = true
                    Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("r-x------"))
                    return super.isDirectory()
                }
            }
        try {
            val newer = JSONObject().put("uris", org.json.JSONArray(listOf("content://private/newer")))
            assertThrows(IOException::class.java) { writePrivateShareJson(deniedReplacement, newer) }
            assertTrue("the fault must occur at replacement, not startWrite", renameReached)
            assertEquals(older.toString(), base.readText())
        } finally {
            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
            root.deleteRecursively()
        }
    }

    /** Sparse originals exercise the exact native-compatible plaintext boundary without copying a huge body. */
    @Test
    fun restoredDocumentValidatesActualLengthAgainstTheSameNativePolicy() {
        val root = Files.createTempDirectory("large-share-recovery").toFile()
        val maximum = dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_ATTACHMENT_MAX_BYTES
        val limits =
            shareImportLimits(
                dev.ipf.whitenoise.android.ui.conversation.media
                    .FileBackedSendLimits(maximum, 900L * 1024L * 1024L),
            )
        val files = PrivateShareFiles(root, "test.private-share", limits)
        try {
            val (uri, file) = files.newFile()
            java.io.RandomAccessFile(file, "rw").use { it.setLength(maximum) }
            files.finish(uri, "large.zip", "application/zip", maximum)
            assertEquals(maximum, PrivateShareFiles(root, "test.private-share", limits).metadata(uri)!!.getLong("size"))
            java.io.RandomAccessFile(file, "rw").use { it.setLength(maximum + 1L) }
            writePrivateShareJson(
                File(root, "${file.nameWithoutExtension}.json"),
                JSONObject().put("name", "large.zip").put("mime", "application/zip").put("size", maximum + 1L),
            )
            org.junit.Assert.assertNull(files.metadata(uri))
        } finally {
            root.deleteRecursively()
        }
    }
}
