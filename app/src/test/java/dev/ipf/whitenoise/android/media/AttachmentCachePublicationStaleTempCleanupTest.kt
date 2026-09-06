package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/** Focused wipe race coverage kept separate from the already large publication suite. */
class AttachmentCachePublicationStaleTempCleanupTest {
    private lateinit var dir: File

    /** Resets global seams and removes the isolated cache directory. */
    @After
    fun tearDown() {
        AttachmentCachePublication.commitAwaiterForTests = null
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    /** Proves stale rejection removes plaintext even when the wipe does not sweep its directory. */
    @Test
    fun publish_withStaleWipeGeneration_alwaysDeletesCompletedTempFile() {
        dir = Files.createTempDirectory("attachment-cache-wipe-temp-race").toFile()
        val voiceDir = File(dir, "voice_attachments").apply { mkdirs() }
        val finalFile = File(voiceDir, "msg-wipe-temp.m4a")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-wipe-temp", 0, 1uL)
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        val writerReachedCommit = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        AttachmentCachePublication.commitAwaiterForTests = {
            writerReachedCommit.complete(Unit)
            runBlocking { releaseWriter.await() }
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val published =
                executor.submit<Boolean> {
                    AttachmentCachePublication.publishWithPermit(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        bytes = byteArrayOf(1, 2, 3),
                        permit = permit,
                    )
                }
            runBlocking { writerReachedCommit.await() }
            assertTrue(
                "the completed plaintext temp file must exist before commit",
                voiceDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
            )

            AttachmentCachePublication.onWipeStarted()
            try {
                releaseWriter.complete(Unit)

                assertFalse("a pre-wipe permit must be rejected", published.get())
                assertFalse(finalFile.exists())
                assertTrue(
                    "stale rejection must delete plaintext when the wipe leaves the directory in place",
                    voiceDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
                )
            } finally {
                AttachmentCachePublication.onWipeFinished()
            }
        } finally {
            releaseWriter.complete(Unit)
            executor.shutdownNow()
        }
    }
}
