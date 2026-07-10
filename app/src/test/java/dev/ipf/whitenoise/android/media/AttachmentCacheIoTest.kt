package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentCacheIoTest {
    private lateinit var dir: File

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun writeBytesAtomically_publishesCompleteFileWithoutTempArtifacts() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-1.mp4")
        val payload = ByteArray(40) { it.toByte() }

        AttachmentCacheIo.writeBytesAtomically(finalFile, payload)

        assertArrayEquals(payload, finalFile.readBytes())
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no .tmp file should linger after successful publish", tmpFiles.isEmpty())
    }

    @Test
    fun writeBytesAtomically_doesNotPublishWhenRenameFails() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-2.mp4")
        finalFile.mkdirs()
        val payload = byteArrayOf(9, 8, 7)

        val published =
            runCatching { AttachmentCacheIo.writeBytesAtomically(finalFile, payload) }.isSuccess

        assertFalse("rename onto an occupied final path must not publish", published)
        assertTrue("final path must remain the blocking directory", finalFile.isDirectory)
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("failed publish must not leave orphan temp files", tmpFiles.isEmpty())
    }

    @Test
    fun writeBytesAtomically_rejectsEmptyPayloadWithoutClaimingPublication() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-3.mp4")

        val published =
            runCatching { AttachmentCacheIo.writeBytesAtomically(finalFile, byteArrayOf()) }.isSuccess

        assertFalse("an empty payload must not report a published cache file", published)
        assertFalse("an empty payload must not create the final path", finalFile.exists())
    }
}
