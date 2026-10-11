package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
    fun overlappingSamePathPublicationsRemainProtectedUntilLastRelease() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val active = cacheFile(directory, "same-path.mp4", 8, 1_000L)

        AttachmentPlaintextCache.protectPublicationFile(active)
        AttachmentPlaintextCache.protectPublicationFile(active)
        try {
            AttachmentPlaintextCache.unprotectPublicationFile(active)
            val stillProtected = AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)

            assertEquals(8L, stillProtected)
            assertTrue(active.exists())
        } finally {
            AttachmentPlaintextCache.unprotectPublicationFile(active)
        }

        assertEquals(0L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L))
        assertFalse(active.exists())
    }

    /**
     * Every playback directory admits one entry up to MarmotKit's attachment ceiling, so a large audio file or video
     * can be played and saved, and none admits a byte more.
     */
    @Test
    fun everyDirectoryAdmitsEntriesUpToTheAttachmentCeiling() {
        val files =
            listOf(
                File(File(root, MediaCacheDirs.VOICE), "large.m4a"),
                File(File(root, MediaCacheDirs.VIDEO), "large.mp4"),
                File(File(root, MediaCacheDirs.SHARED), "large.bin"),
            )

        for (file in files) {
            AttachmentPlaintextCache.requireEntryWithinLimit(file, AttachmentPlaintextCache.ENTRY_MAX_BYTES)
            assertThrows(IOException::class.java) {
                AttachmentPlaintextCache.requireEntryWithinLimit(file, AttachmentPlaintextCache.ENTRY_MAX_BYTES + 1L)
            }
        }
    }

    /** Publishing an entry larger than the budget keeps it, and holds only the rest of the directory to the budget. */
    @Test
    fun anOversizedPublicationKeepsItsNeighboursWithinTheBudget() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val oldest = cacheFile(directory, "oldest.mp4", 4, 1_000L)
        val middle = cacheFile(directory, "middle.mp4", 6, 1_500L)
        val large = cacheFile(directory, "large.mp4", 16, 2_000L)

        assertEquals(22L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = large))
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(large.exists())
    }

    /** Smaller publications evict around the newest oversized entry instead of evicting it. */
    @Test
    fun smallerPublicationsKeepTheNewestOversizedEntry() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val large = cacheFile(directory, "large.mp4", 16, 1_000L)
        val small = cacheFile(directory, "small.mp4", 4, 2_000L)
        val published = cacheFile(directory, "published.mp4", 6, 3_000L)

        assertEquals(22L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = published))
        assertTrue(large.exists())
        assertFalse(small.exists())
        assertTrue(published.exists())
    }

    /** A newer oversized entry replaces the older one, so at most one is kept beside the budget. */
    @Test
    fun aNewerOversizedEntryReplacesTheOlderOne() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val older = cacheFile(directory, "older.mp4", 16, 1_000L)
        val newer = cacheFile(directory, "newer.mp4", 20, 2_000L)

        assertEquals(20L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = newer))
        assertFalse(older.exists())
        assertTrue(newer.exists())

        val newest = cacheFile(directory, "newest.mp4", 12, 3_000L)
        assertEquals(12L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L))
        assertFalse(newer.exists())
        assertTrue(newest.exists())
    }

    /** A trim with nothing being published, as at app start, keeps the most recently used oversized entry. */
    @Test
    fun anUnprotectedTrimKeepsTheMostRecentlyUsedOversizedEntry() {
        val directory = File(root, MediaCacheDirs.VOICE).apply { mkdirs() }
        val small = cacheFile(directory, "small.m4a", 4, 1_000L)
        val large = cacheFile(directory, "large.m4a", 16, 2_000L)

        assertEquals(20L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L))
        assertTrue(small.exists())
        assertTrue(large.exists())
    }

    /** A large entry displaced by a newer one goes before any smaller file, so the smaller ones stay. */
    @Test
    fun aDisplacedOversizedEntryGoesBeforeSmallerFiles() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val small = cacheFile(directory, "small.mp4", 4, 1_000L)
        val displaced = cacheFile(directory, "displaced.mp4", 16, 1_500L)
        val newer = cacheFile(directory, "newer.mp4", 20, 2_000L)

        assertEquals(24L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = newer))
        assertTrue(small.exists())
        assertFalse(displaced.exists())
        assertTrue(newer.exists())
    }

    /**
     * A size trim leaves the newest shared files alone while an app they were handed to may still be reading them,
     * and evicts each once its window has passed. A budget of zero still clears them.
     */
    @Test
    fun sharedFilesOutlastSizeTrimsForTheirReaderWindow() {
        val directory = File(root, MediaCacheDirs.SHARED).apply { mkdirs() }
        val now = 100_000_000L
        val window = AttachmentPlaintextCache.SHARED_READER_WINDOW_MILLIS
        val stale = cacheFile(directory, "stale.pdf", 4, now - window - 1_000L)
        val handed = cacheFile(directory, "handed.pdf", 4, now - 60_000L)
        val newer = cacheFile(directory, "newer.pdf", 4, now - 30_000L)

        assertEquals(8L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 10L, nowMillis = now))
        assertFalse(stale.exists())
        assertTrue(handed.exists())
        assertTrue(newer.exists())

        val latest = cacheFile(directory, "latest.pdf", 4, now)
        val later = now + window - 45_000L
        assertEquals(8L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 10L, nowMillis = later))
        assertFalse(handed.exists())
        assertTrue(newer.exists())
        assertTrue(latest.exists())

        assertEquals(0L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L, nowMillis = now))
        assertFalse(newer.exists())
        assertFalse(latest.exists())
    }

    /** The reader window keeps the newest shared files only up to the budget, so it cannot outgrow it. */
    @Test
    fun theReaderWindowHoldsAtMostTheBudget() {
        val directory = File(root, MediaCacheDirs.SHARED).apply { mkdirs() }
        val now = 100_000_000L
        val oldest = cacheFile(directory, "oldest.pdf", 6, now - 3_000L)
        val middle = cacheFile(directory, "middle.pdf", 6, now - 2_000L)
        val newest = cacheFile(directory, "newest.pdf", 6, now - 1_000L)

        assertEquals(6L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 10L, nowMillis = now))
        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }

    /** A held file larger than the budget does not count against it, so its smaller neighbours stay within it. */
    @Test
    fun aHeldOversizedFileLeavesItsNeighboursWithinTheBudget() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val held = cacheFile(directory, "held.mp4", 16, 1_000L)
        val small = cacheFile(directory, "small.mp4", 4, 2_000L)
        val published = cacheFile(directory, "published.mp4", 20, 3_000L)

        AttachmentPlaintextCache.protectPublicationFile(held)
        try {
            assertEquals(40L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = published))
        } finally {
            AttachmentPlaintextCache.unprotectPublicationFile(held)
        }
        assertTrue(held.exists())
        assertTrue(small.exists())
        assertTrue(published.exists())
    }

    /** A large send that needs room drops the large file each playback cache keeps, except one still in use. */
    @Test
    fun droppingKeptEntriesFreesUnheldOversizedFiles() {
        val video = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val voice = File(root, MediaCacheDirs.VOICE).apply { mkdirs() }
        val keptVideo = sparseCacheFile(video, "kept.mp4", AttachmentPlaintextCache.VIDEO_MAX_DIRECTORY_BYTES + 1L)
        val smallVideo = cacheFile(video, "small.mp4", 4, 1_000L)
        val heldAudio = sparseCacheFile(voice, "held.m4a", AttachmentPlaintextCache.VOICE_MAX_DIRECTORY_BYTES + 1L)

        AttachmentPlaintextCache.protectPublicationFile(heldAudio)
        try {
            dropKeptOversizedCacheEntries(root)
        } finally {
            AttachmentPlaintextCache.unprotectPublicationFile(heldAudio)
        }

        assertFalse(keptVideo.exists())
        assertTrue(smallVideo.exists())
        assertTrue(heldAudio.exists())
    }

    /** A file held in use survives every trim until its hold is released. */
    @Test
    fun aFileHeldInUseSurvivesTrimsUntilReleased() {
        val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val playing = cacheFile(directory, "playing.mp4", 6, 1_000L)
        val published = cacheFile(directory, "published.mp4", 6, 2_000L)

        holdCacheFileInUse(playing)
        try {
            assertEquals(12L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = published))
            assertTrue(playing.exists())
        } finally {
            releaseCacheFileInUse(playing)
        }

        assertEquals(6L, AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 8L, protectedFile = published))
        assertFalse(playing.exists())
    }

    @Test
    fun sharedFileHandoffAdmitsLargeApksWithoutRemovingTheByteCeiling() {
        val sharedFile = File(File(root, MediaCacheDirs.SHARED), "release.apk")
        val arm64ApkBytes = 143_162_216L
        val universalApkBytes = 261_046_818L

        AttachmentPlaintextCache.requireEntryWithinLimit(sharedFile, arm64ApkBytes)
        AttachmentPlaintextCache.requireEntryWithinLimit(sharedFile, universalApkBytes)
        assertThrows(IOException::class.java) {
            AttachmentPlaintextCache.requireEntryWithinLimit(
                sharedFile,
                AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES + 1L,
            )
        }
    }

    @Test
    fun finishingPublicationAtomicallyReleasesItAndTrimsOlderEntries() {
        val directory = File(root, MediaCacheDirs.SHARED).apply { mkdirs() }
        val older = cacheFile(directory, "older.bin", 8, 1_000L)
        val published =
            File(directory, "published.bin").apply {
                RandomAccessFile(this, "rw").use { it.setLength(AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES) }
                assertTrue(setLastModified(2_000L))
            }
        AttachmentPlaintextCache.protectPublicationFile(published)

        AttachmentPlaintextCache.finishPublication(published)

        assertFalse(older.exists())
        assertTrue(published.exists())
    }

    /** A sparse cache file of [bytes], so a test can exceed a real budget without writing it. */
    private fun sparseCacheFile(
        directory: File,
        name: String,
        bytes: Long,
    ): File = File(directory, name).apply { RandomAccessFile(this, "rw").use { it.setLength(bytes) } }

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
