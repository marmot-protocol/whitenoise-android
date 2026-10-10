package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadSourcesCleanupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    /** An earlier process's snapshots go at any age; this process's stay, however old, for a pending Retry. */
    @Test
    fun sweepRemovesEarlierProcessesSnapshotsButNeverThisProcesss() {
        val cache = temporary.newFolder("cache")
        val own = File(uploadSourcesDirectory(cache).apply { mkdirs() }, "upload-source-own").apply { writeText("own") }
        own.setLastModified(0L)
        val earlier = File(File(cache, MediaCacheDirs.UPLOAD_SOURCES), "process-earlier").apply { mkdirs() }
        val orphan = File(earlier, "upload-source-orphan").apply { writeText("orphan") }

        sweepOrphanedUploadSources(cache)
        sweepOrphanedUploadSources(cache)

        assertTrue("a long-pending Retry keeps its snapshot across Activity recreation", own.exists())
        assertFalse(orphan.exists())
        assertFalse(earlier.exists())
    }

    /** Sign-out removes every staged plaintext snapshot, this process's included. */
    @Test
    fun signOutWipeRemovesEveryStagedSnapshot() {
        val cache = temporary.newFolder("cache")
        val own = File(uploadSourcesDirectory(cache).apply { mkdirs() }, "upload-source-own").apply { writeText("own") }

        wipeSessionAttachmentPlaintext(cache) { name, failure -> throw AssertionError(name, failure) }

        assertFalse(own.exists())
        assertFalse(File(cache, MediaCacheDirs.UPLOAD_SOURCES).exists())
    }
}
