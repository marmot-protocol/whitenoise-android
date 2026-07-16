package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AttachmentPlaintextCacheTest {
    private val root = Files.createTempDirectory("attachment-plaintext-cache").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun trimDeletesLeastRecentlyUsedFilesUntilDirectoryFits() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val oldest = cacheFile(directory, "oldest.mp4", 4, 1_000L)
        val middle = cacheFile(directory, "middle.mp4", 4, 2_000L)
        val newest = cacheFile(directory, "newest.mp4", 4, 3_000L)

        val remaining = AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = newest)

        assertEquals(8L, remaining)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun cacheHitTouchPromotesFileAheadOfOlderEntries() {
        val directory = File(root, MediaCacheDirs.VOICE).apply { mkdirs() }
        val first = cacheFile(directory, "first.m4a", 4, 1_000L)
        val second = cacheFile(directory, "second.m4a", 4, 2_000L)
        val newest = cacheFile(directory, "newest.m4a", 4, 3_000L)
        AttachmentPlaintextCache.touch(first, nowMillis = 4_000L)

        AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = newest)

        assertTrue(first.exists())
        assertFalse(second.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun activePublicationTempIsProtectedButAbandonedTempIsEvicted() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val finalFile = cacheFile(directory, "final.mp4", 4, 1_000L)
        val temp = cacheFile(directory, "active.cache.tmp", 32, 500L)

        AttachmentPlaintextCache.protectPublicationFile(temp)
        val activeRemaining =
            try {
                AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 4L, protectedFile = finalFile)
            } finally {
                AttachmentPlaintextCache.unprotectPublicationFile(temp)
            }
        val settledRemaining = AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 4L, protectedFile = finalFile)

        assertEquals(36L, activeRemaining)
        assertEquals(4L, settledRemaining)
        assertTrue(finalFile.exists())
        assertFalse(temp.exists())
    }

    @Test
    fun publicationRejectsSingleEntryLargerThanDirectoryLimit() {
        val voiceFile = File(File(root, MediaCacheDirs.VOICE), "too-large.m4a")
        val videoFile = File(File(root, MediaCacheDirs.VIDEO), "too-large.mp4")

        assertThrows(IOException::class.java) {
            AttachmentPlaintextCache.requireEntryWithinLimit(
                voiceFile,
                AttachmentPlaintextCache.VOICE_MAX_DIRECTORY_BYTES + 1L,
            )
        }
        assertThrows(IOException::class.java) {
            AttachmentPlaintextCache.requireEntryWithinLimit(
                videoFile,
                AttachmentPlaintextCache.VIDEO_MAX_DIRECTORY_BYTES + 1L,
            )
        }
    }

    private fun cacheFile(
        directory: File,
        name: String,
        bytes: Int,
        modifiedAt: Long,
    ): File =
        File(directory, name).apply {
            writeBytes(ByteArray(bytes) { it.toByte() })
            assertTrue(setLastModified(modifiedAt))
        }
}
