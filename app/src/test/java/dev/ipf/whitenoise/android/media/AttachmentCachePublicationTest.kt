package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AttachmentCachePublicationTest {
    private lateinit var dir: File

    /** Resets global test seams and removes every publication artifact. */
    @After
    fun tearDown() {
        AttachmentCachePublication.commitAwaiterForTests = null
        AttachmentCachePublication.renameFileForTests = null
        AttachmentCachePublication.deleteFileForTests = null
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    /** Establishes the complete-file and no-orphan baseline for byte publication. */
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

    /** Forces the copy fallback and proves closing removes its private source lease. */
    @Test
    fun publishSourceAfterLoad_streamsAndClosesPrivateLease() =
        runBlocking {
            dir = Files.createTempDirectory("attachment-source").toFile()
            val sourceFile = File(dir, "source.lease").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val copyOnlySource =
                object : File(sourceFile.absolutePath) {
                    /** Forces the deterministic copy fallback regardless of host filesystem behavior. */
                    override fun renameTo(dest: File): Boolean = false
                }
            val finalFile = File(dir, "final.apk")
            val key = AttachmentCachePublication.attachmentKey("msg-source", 0, 1uL)

            val published =
                AttachmentCachePublication.publishSourceAfterLoad(key, finalFile) {
                    AttachmentPlaintext.Lease(DiskByteCacheLease(copyOnlySource))
                }

            assertTrue(published)
            assertArrayEquals(byteArrayOf(4, 5, 6), finalFile.readBytes())
            assertFalse("the copy path must close and delete its source lease", sourceFile.exists())
        }

    /** Proves a wipe between permit capture and source loading rejects publication. */
    @Test
    fun publishSourceAfterLoad_rejectsSourceLoadedAcrossWipe() =
        runBlocking {
            dir = Files.createTempDirectory("attachment-source-wipe").toFile()
            val source = File(dir, "source.lease").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val finalFile = File(dir, "final.apk")
            val key = AttachmentCachePublication.attachmentKey("msg-wipe", 0, 1uL)

            val published =
                AttachmentCachePublication.publishSourceAfterLoad(key, finalFile) {
                    AttachmentCachePublication.onWipeStarted()
                    AttachmentCachePublication.onWipeFinished()
                    AttachmentPlaintext.Lease(DiskByteCacheLease(source))
                }

            assertFalse(published)
            assertFalse(finalFile.exists())
            assertFalse(source.exists())
        }

    /** Proves a destination preparation failure still releases acquired plaintext. */
    @Test
    fun publishSourceAfterLoad_destinationFailureStillClosesLease() =
        runBlocking {
            dir = Files.createTempDirectory("attachment-source-failure").toFile()
            val source = File(dir, "source.lease").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val blockingParent = File(dir, "not-a-directory").apply { writeText("block") }
            val finalFile = File(blockingParent, "final.apk")
            val key = AttachmentCachePublication.attachmentKey("msg-failure", 0, 1uL)

            val published =
                AttachmentCachePublication.publishSourceAfterLoad(key, finalFile) {
                    AttachmentPlaintext.Lease(DiskByteCacheLease(source))
                }

            assertFalse(published)
            assertFalse(source.exists())
            assertFalse(finalFile.exists())
        }

    /** Rejects a source whose streamed length disagrees with its declared bound. */
    @Test
    fun publishSourceAfterLoad_rejectsChangedSourceLength() =
        runBlocking {
            dir = Files.createTempDirectory("attachment-source-length").toFile()
            val finalFile = File(dir, "final.apk")
            val key = AttachmentCachePublication.attachmentKey("msg-length", 0, 1uL)
            val source = ChangedLengthPlaintext(size = 4L, bytes = byteArrayOf(1, 2, 3))

            val published = AttachmentCachePublication.publishSourceAfterLoad(key, finalFile) { source }

            assertFalse(published)
            assertFalse(finalFile.exists())
            assertTrue(dir.listFiles()?.none { it.name.endsWith(".tmp") } ?: true)
        }

    /** Ensures a failed atomic rename cannot claim publication or strand temp files. */
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

    private class ChangedLengthPlaintext(
        override val size: Long,
        private val bytes: ByteArray,
    ) : AttachmentPlaintext {
        /** Writes the intentionally shorter payload used to exercise length validation. */
        override fun copyTo(output: OutputStream) = output.write(bytes)

        /** This synthetic source holds no external resource. */
        override fun close() = Unit
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
    fun renameIoOnOneStripeDoesNotBlockPermitCaptureOnAnotherStripe() {
        dir = Files.createTempDirectory("attachment-cache-rename-stripe").toFile()
        val attachmentKey = AttachmentCachePublication.attachmentKey("rename-source", 0, 1uL)
        val unrelatedKey = keyOnDifferentStripe(attachmentKey, "rename-other")
        val finalFile = File(dir, "rename-source.mp4")
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        val renameEntered = CountDownLatch(1)
        val releaseRename = CountDownLatch(1)
        AttachmentCachePublication.renameFileForTests = { source, target ->
            renameEntered.countDown()
            check(releaseRename.await(5, TimeUnit.SECONDS))
            source.renameTo(target)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publish =
                executor.submit<Boolean> {
                    AttachmentCachePublication.publishWithPermit(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        bytes = byteArrayOf(1, 2, 3),
                        permit = permit,
                    )
                }
            assertTrue(renameEntered.await(5, TimeUnit.SECONDS))

            val unrelatedPermit = executor.submit<AttachmentCachePublication.Permit?> { AttachmentCachePublication.capturePermit(unrelatedKey) }
            assertNotNull(unrelatedPermit.get(1, TimeUnit.SECONDS))

            releaseRename.countDown()
            assertTrue(publish.get(5, TimeUnit.SECONDS))
        } finally {
            releaseRename.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun sameStripeWaiterDoesNotConvoyAnUnrelatedPermitCapture() {
        dir = Files.createTempDirectory("attachment-cache-permit-convoy").toFile()
        val attachmentKey = AttachmentCachePublication.attachmentKey("convoy-source", 0, 1uL)
        val unrelatedKey = keyOnDifferentStripe(attachmentKey, "convoy-other")
        val finalFile = File(dir, "convoy-source.mp4")
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        val renameEntered = CountDownLatch(1)
        val releaseRename = CountDownLatch(1)
        AttachmentCachePublication.renameFileForTests = { source, target ->
            renameEntered.countDown()
            check(releaseRename.await(10, TimeUnit.SECONDS))
            source.renameTo(target)
        }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val publish =
                executor.submit<Boolean> {
                    AttachmentCachePublication.publishWithPermit(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        bytes = byteArrayOf(1, 2, 3),
                        permit = permit,
                    )
                }
            assertTrue(renameEntered.await(5, TimeUnit.SECONDS))

            val waiterThread = AtomicReference<Thread>()
            val sameStripePermit =
                executor.submit<AttachmentCachePublication.Permit?> {
                    waiterThread.set(Thread.currentThread())
                    AttachmentCachePublication.capturePermit(attachmentKey)
                }
            assertTrue(waitUntilBlocked(waiterThread))

            val unrelatedPermit = executor.submit<AttachmentCachePublication.Permit?> { AttachmentCachePublication.capturePermit(unrelatedKey) }
            assertNotNull(unrelatedPermit.get(2, TimeUnit.SECONDS))

            releaseRename.countDown()
            assertTrue(publish.get(5, TimeUnit.SECONDS))
            assertNotNull(sameStripePermit.get(5, TimeUnit.SECONDS))
        } finally {
            releaseRename.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun deleteIoOnOneStripeDoesNotBlockPermitCaptureOnAnotherStripe() {
        dir = Files.createTempDirectory("attachment-cache-delete-stripe").toFile()
        val attachmentKey = AttachmentCachePublication.attachmentKey("delete-source", 0, 1uL)
        val unrelatedKey = keyOnDifferentStripe(attachmentKey, "delete-other")
        val finalFile = File(dir, "delete-source.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val deleteEntered = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        val deleteCalls = AtomicInteger()
        AttachmentCachePublication.deleteFileForTests = { file ->
            if (deleteCalls.incrementAndGet() == 1) {
                deleteEntered.countDown()
                check(releaseDelete.await(5, TimeUnit.SECONDS))
            }
            file.delete()
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invalidation =
                executor.submit {
                    runBlocking {
                        AttachmentCachePublication.invalidateAttachmentCache(
                            attachmentKey = attachmentKey,
                            finalFile = finalFile,
                            evictPlaintext = {},
                        )
                    }
                }
            assertTrue(deleteEntered.await(5, TimeUnit.SECONDS))

            val unrelatedPermit = executor.submit<AttachmentCachePublication.Permit?> { AttachmentCachePublication.capturePermit(unrelatedKey) }
            assertNotNull(unrelatedPermit.get(1, TimeUnit.SECONDS))

            releaseDelete.countDown()
            invalidation.get(5, TimeUnit.SECONDS)
            assertFalse(finalFile.exists())
        } finally {
            releaseDelete.countDown()
            executor.shutdownNow()
        }
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
                        try {
                            voiceDir.deleteRecursively()
                            payload
                        } finally {
                            AttachmentCachePublication.onWipeFinished()
                        }
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
            try {
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
                AttachmentCachePublication.onWipeFinished()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** Proves a wipe cannot finish between final validation and plaintext rename. */
    @Test
    fun wipeWaitsForAcceptedFinalPublicationBeforeDeletingTheFile() {
        dir = Files.createTempDirectory("attachment-cache-wipe-publication").toFile()
        val voiceDir = File(dir, "voice_attachments").apply { mkdirs() }
        val finalFile = File(voiceDir, "msg-wipe-publication.m4a")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-wipe-publication", 0, 1uL)
        val permit = AttachmentCachePublication.capturePermit(attachmentKey)!!
        val wipeEntered = CountDownLatch(1)
        val wipeFinished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        AttachmentCachePublication.renameFileForTests = { source, target ->
            executor.submit {
                wipeEntered.countDown()
                AttachmentCachePublication.onWipeStarted()
                try {
                    voiceDir.deleteRecursively()
                } finally {
                    AttachmentCachePublication.onWipeFinished()
                    wipeFinished.countDown()
                }
            }
            assertTrue(wipeEntered.await(5, TimeUnit.SECONDS))
            assertFalse(
                "wipe must wait while final publication owns the generation fence",
                wipeFinished.await(100, TimeUnit.MILLISECONDS),
            )
            source.renameTo(target)
        }
        try {
            assertTrue(
                AttachmentCachePublication.publishWithPermit(
                    attachmentKey = attachmentKey,
                    finalFile = finalFile,
                    bytes = byteArrayOf(1, 2, 3),
                    permit = permit,
                ),
            )
            assertTrue(wipeFinished.await(5, TimeUnit.SECONDS))
            assertFalse(finalFile.exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun publishStartedDuringWipe_isRejectedUntilWipeFinishes() {
        dir = Files.createTempDirectory("attachment-cache-active-wipe").toFile()
        val finalFile = File(File(dir, "voice_attachments"), "msg-active-wipe.m4a")
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-active-wipe", 0, 1uL)
        var loadCalled = false

        AttachmentCachePublication.onWipeStarted()
        try {
            val published =
                runBlocking {
                    AttachmentCachePublication.publishAfterLoad(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        loadBytes = {
                            loadCalled = true
                            byteArrayOf(1, 2, 3)
                        },
                    )
                }

            assertFalse("a writer starting during wipe must not receive a permit", published)
            assertFalse("a rejected writer must not load plaintext", loadCalled)
            assertFalse(finalFile.exists())
        } finally {
            AttachmentCachePublication.onWipeFinished()
        }

        assertNotNull(
            "publication should resume after the wipe finishes",
            AttachmentCachePublication.capturePermit(attachmentKey),
        )
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
    fun cancellationDuringEvictionIsNotMaskedByAFinalDeleteFailure() {
        dir = Files.createTempDirectory("attachment-cache-cancel-eviction").toFile()
        val finalFile = File(dir, "cancel.mp4").apply { writeBytes(byteArrayOf(1)) }
        val attachmentKey = AttachmentCachePublication.attachmentKey("msg-cancel", 0, 1uL)
        val deleteCalls = AtomicInteger()
        AttachmentCachePublication.deleteFileForTests = {
            if (deleteCalls.incrementAndGet() == 1) {
                true
            } else {
                throw IOException("final delete failed")
            }
        }

        val failure =
            runCatching {
                runBlocking {
                    AttachmentCachePublication.invalidateAttachmentCache(
                        attachmentKey = attachmentKey,
                        finalFile = finalFile,
                        evictPlaintext = { throw CancellationException("cancelled") },
                    )
                }
            }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertNotNull(AttachmentCachePublication.capturePermit(attachmentKey))
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

    private fun keyOnDifferentStripe(
        reference: String,
        prefix: String,
    ): String =
        generateSequence(0) { it + 1 }
            .map { AttachmentCachePublication.attachmentKey("$prefix-$it", 0, 1uL) }
            .first { candidate ->
                AttachmentCachePublication.stripeIndex(candidate) != AttachmentCachePublication.stripeIndex(reference)
            }

    private fun waitUntilBlocked(threadReference: AtomicReference<Thread>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (threadReference.get()?.state == Thread.State.BLOCKED) return true
            Thread.sleep(5)
        }
        return false
    }
}
