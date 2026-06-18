package dev.ipf.darkmatter.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class MediaPipelineBoundedReadTest {
    @Test
    fun readsExactPayload_whenUnderCap() {
        val source = ByteArray(1024) { i -> (i % 256).toByte() }
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 2048)
        assertArrayEquals(source, result)
    }

    @Test
    fun readsExactPayload_atCapBoundary() {
        // A payload exactly the size of the cap MUST go through. A
        // strict-greater-than check is what makes this work; a
        // greater-than-or-equal check would reject the boundary.
        val source = ByteArray(4096) { 0x42 }
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 4096)
        assertArrayEquals(source, result)
    }

    @Test
    fun returnsNull_oneByteOverCap() {
        val source = ByteArray(4097) { 0x42 }
        assertNull(MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 4096))
    }

    @Test
    fun returnsNull_whenWildlyOverCap() {
        // Bounded loop must not buffer the whole payload before noticing. We
        // can't directly assert that here, but a `null` result on a payload
        // multiples of cap large at least proves the abort path runs.
        val source = ByteArray(1 * 1024 * 1024) { 0x42 }
        assertNull(MediaPipeline.readBoundedBytes(ByteArrayInputStream(source), cap = 8 * 1024))
    }

    @Test
    fun readsEmptyStream() {
        val result = MediaPipeline.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), cap = 1024)
        assertArrayEquals(ByteArray(0), result)
    }

    @Test
    fun copiesStreamToFileAtCapBoundary() {
        val source = ByteArray(64 * 1024) { i -> (i % 251).toByte() }
        val tmp = File.createTempFile("media-pipeline-copy-", ".bin")
        try {
            assertTrue(MediaPipeline.copyStreamToFileWithinCap(ByteArrayInputStream(source), tmp, cap = source.size.toLong()))
            assertArrayEquals(source, tmp.readBytes())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun copyStreamToFileRejectsOneByteOverCapWithoutWritingOverflow() {
        val cap = 64 * 1024L
        val source = ByteArray(cap.toInt() + 1) { i -> (i % 251).toByte() }
        val tmp = File.createTempFile("media-pipeline-copy-", ".bin")
        try {
            assertFalse(MediaPipeline.copyStreamToFileWithinCap(ByteArrayInputStream(source), tmp, cap = cap))
            assertTrue("overflow chunk must not be written", tmp.length() <= cap)
            assertArrayEquals(source.copyOfRange(0, tmp.length().toInt()), tmp.readBytes())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun readsFileBytesExactly() {
        val source = ByteArray(128 * 1024) { i -> (i % 251).toByte() }
        val tmp = File.createTempFile("media-pipeline-read-", ".bin")
        try {
            tmp.writeBytes(source)
            assertArrayEquals(source, MediaPipeline.readFileBytesExact(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun createsVideoMetadataTempFilesUnderWipeCoveredVideoCache() {
        val cacheRoot = Files.createTempDirectory("media-pipeline-cache-").toFile()
        val tmp = MediaPipeline.createVideoMetadataTempFile(cacheRoot)
        try {
            assertTrue("metadata temp file should be created", tmp != null)
            val tmpFile = tmp!!
            assertTrue("metadata temp file should exist", tmpFile.exists())
            assertTrue("metadata temp file should use vidmeta prefix", tmpFile.name.startsWith("vidmeta-"))
            assertEquals(File(cacheRoot, MediaCacheDirs.VIDEO).canonicalFile, tmpFile.parentFile!!.canonicalFile)
        } finally {
            tmp?.delete()
            cacheRoot.deleteRecursively()
        }
    }
}
