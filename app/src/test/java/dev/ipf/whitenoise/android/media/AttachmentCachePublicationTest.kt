package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class AttachmentCachePublicationTest {
    private lateinit var dir: File

    @After
    fun tearDown() {
        AttachmentCachePublication.commitAwaiterForTests = null
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun publishWithPermit_publishesCompleteFileWithoutTempArtifacts() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-1.mp4")
        val payload = ByteArray(40) { it.toByte() }
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-1", 0, 1uL)
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!

        val published =
            AttachmentCachePublication.publishWithPermit(
                attachmentKey = attachmentKey,
                finalFile = finalFile,
                bytes = payload,
                permit = permit,
            )

        assertTrue(published)
        assertArrayEquals(payload, finalFile.readBytes())
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no .tmp file should linger after successful publish", tmpFiles.isEmpty())
    }

    @Test
    fun publishWithPermit_doesNotPublishWhenRenameFails() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-2.mp4")
        finalFile.mkdirs()
        val payload = byteArrayOf(9, 8, 7)
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-2", 0, 1uL)
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!

        val published =
            runCatching {
                AttachmentCachePublication.publishWithPermit(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    bytes = payload,
                    permit = permit,
                )
            }.isSuccess

        assertFalse("rename onto an occupied final path must not publish", published)
        assertTrue("final path must remain the blocking directory", finalFile.isDirectory)
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("failed publish must not leave orphan temp files", tmpFiles.isEmpty())
    }

    @Test
    fun publishWithPermit_rejectsEmptyPayloadWithoutClaimingPublication() {
        dir = Files.createTempDirectory("attachment-cache-io").toFile()
        val finalFile = File(dir, "msg-3.mp4")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-3", 0, 1uL)
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!

        val published =
            runCatching {
                AttachmentCachePublication.publishWithPermit(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    bytes = byteArrayOf(),
                    permit = permit,
                )
            }.isSuccess

        assertFalse("an empty payload must not report a published cache file", published)
        assertFalse("an empty payload must not create the final path", finalFile.exists())
    }

    @Test
    fun publishAfterLoad_rejectsWhenWipeStartsDuringPlaintextLoad() {
        dir = Files.createTempDirectory("attachment-cache-wipe-during-load").toFile()
        val voiceDir = File(dir, "voice_attachments")
        val finalFile = File(voiceDir, "msg-wipe-load-1.m4a")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-wipe-load", 1, 1uL)
        val payload = ByteArray(16) { it.toByte() }

        val published =
            runBlocking {
                AttachmentCachePublication.publishAfterLoad(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    loadBytes = {
                        AttachmentCachePublication.onWipeStarted()
                        voiceDir.deleteRecursively()
                        payload
                    },
                )
            }

        assertFalse(
            "bytes loaded after wipe must not publish under a pre-wipe permit",
            published,
        )
        assertFalse(
            "stale publication must not recreate a wiped attachment directory",
            voiceDir.exists(),
        )
        assertFalse(finalFile.exists())
    }

    @Test
    fun publish_withStaleWipeGeneration_doesNotRecreateDirectoryAfterWipe() {
        dir = Files.createTempDirectory("attachment-cache-wipe-race").toFile()
        val voiceDir = File(dir, "voice_attachments")
        voiceDir.mkdirs()
        val finalFile = File(voiceDir, "msg-wipe-1.m4a")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-wipe", 1, 1uL)
        val payload = ByteArray(16) { it.toByte() }
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
                        bytes = payload,
                        permit = permit,
                    )
                }
            runBlocking { writerReachedCommit.await() }
            AttachmentCachePublication.onWipeStarted()
            voiceDir.deleteRecursively()
            releaseWriter.complete(Unit)

            assertFalse(
                "a writer scheduled before wipe must not publish afterward",
                published.get(),
            )
            assertFalse(
                "wipe must not be undone by mkdirs from a stale writer",
                voiceDir.exists(),
            )
            assertFalse(finalFile.exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun publish_afterConcurrentInvalidation_doesNotResurrectDeletedBytes() {
        dir = Files.createTempDirectory("attachment-cache-invalidate-race").toFile()
        val finalFile = File(dir, "msg-inv-1.mp4")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-inv", 1, 2uL)
        val staleBytes = ByteArray(12) { 9 }
        val freshBytes = ByteArray(12) { 3 }
        finalFile.parentFile?.mkdirs()
        val initialPermit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        AttachmentCachePublication.publishWithPermit(
            attachmentKey = attachmentKey,
            finalFile = finalFile,
            bytes = staleBytes,
            permit = initialPermit,
        )

        val stalePermit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        val writerReachedCommit = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        AttachmentCachePublication.commitAwaiterForTests = {
            writerReachedCommit.complete(Unit)
            runBlocking { releaseWriter.await() }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stalePublish =
                executor.submit<Boolean> {
                    AttachmentCachePublication.publishWithPermit(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        bytes = staleBytes,
                        permit = stalePermit,
                    )
                }
            runBlocking { writerReachedCommit.await() }
            runBlocking {
                AttachmentCachePublication.invalidateAttachmentCache(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    evictPlaintext = {},
                )
            }
            releaseWriter.complete(Unit)

            assertFalse(
                "invalidation must reject a publish captured before the bump",
                stalePublish.get(),
            )
            assertFalse(finalFile.exists())

            val freshPermit = AttachmentCachePublication.capturePermit(attachmentKey)!!
            val republished =
                AttachmentCachePublication.publishWithPermit(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    bytes = freshBytes,
                    permit = freshPermit,
                )
            assertTrue(republished)
            assertArrayEquals(freshBytes, finalFile.readBytes())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedInitialDelete_stillEvictsPlaintextAndDoesNotBrickStripe() {
        dir = Files.createTempDirectory("attachment-cache-delete-failure").toFile()
        val blocked = File(dir, "blocked.mp4").apply { mkdirs() }
        File(blocked, "child").writeBytes(byteArrayOf(1))
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-delete-failure", 1, 5uL)
        var evictCalled = false

        val failure =
            runBlocking {
                runCatching {
                    AttachmentCachePublication.invalidateAttachmentCache(
                        attachmentKey = attachmentKey,
                        finalFile = blocked,
                        evictPlaintext = { evictCalled = true },
                    )
                }
            }

        assertTrue(failure.isFailure)
        // A failed first delete must not skip plaintext eviction.
        assertTrue(evictCalled)
        assertNotNull(
            "a failed initial delete must not brick every future publish on the stripe",
            AttachmentCachePublication.capturePermit(attachmentKey),
        )
    }

    @Test
    fun publishDuringEviction_isRejectedAndLeavesNoFinalFile() {
        dir = Files.createTempDirectory("attachment-cache-eviction-window").toFile()
        val finalFile = File(dir, "msg-evict-1.mp4")
        finalFile.parentFile?.mkdirs()
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-evict", 1, 4uL)
        val staleBytes = ByteArray(8) { 1 }
        val corruptBytes = ByteArray(8) { 9 }
        val seedPermit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        AttachmentCachePublication.publishWithPermit(
            attachmentKey = attachmentKey,
            finalFile = finalFile,
            bytes = staleBytes,
            permit = seedPermit,
        )

        val evictionEntered = CompletableDeferred<Unit>()
        val releaseEviction = CompletableDeferred<Unit>()
        var publishedDuringEviction = false
        val executor = Executors.newSingleThreadExecutor()
        try {
            val invalidation =
                executor.submit {
                    runBlocking {
                        AttachmentCachePublication.invalidateAttachmentCache(
                            attachmentKey = attachmentKey,
                            finalFile = finalFile,
                            evictPlaintext = {
                                evictionEntered.complete(Unit)
                                releaseEviction.await()
                            },
                        )
                    }
                }
            runBlocking { evictionEntered.await() }

            val publishPermit = AttachmentCachePublication.capturePermit(attachmentKey)
            publishedDuringEviction =
                if (publishPermit == null) {
                    false
                } else {
                    AttachmentCachePublication.publishWithPermit(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        bytes = corruptBytes,
                        permit = publishPermit,
                    )
                }
            releaseEviction.complete(Unit)
            invalidation.get()

            assertFalse(
                "publication must stay disallowed for the stripe during eviction",
                publishedDuringEviction,
            )
            assertFalse(
                "invalidation must not leave a final file after eviction completes",
                finalFile.exists(),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
