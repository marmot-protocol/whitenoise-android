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
}
