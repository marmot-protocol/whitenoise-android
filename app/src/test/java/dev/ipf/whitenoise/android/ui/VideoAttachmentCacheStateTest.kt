package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVideoAttachmentFile
import dev.ipf.whitenoise.android.ui.conversation.media.invalidateVideoAttachmentCacheAfterPlaybackFailure
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVideoAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.shouldStartVideoAttachmentDownload
import dev.ipf.whitenoise.android.ui.conversation.media.videoAttachmentCacheFileForTests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoAttachmentCacheStateTest {
    private companion object {
        private const val TEST_HANG_GUARD_MS = 30_000L
    }

    @Test
    fun cachedVideoFileIsFoundOnEntry() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-cache-hit"
        val reference = mediaReference(mediaType = "video/mp4")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-1-1.mp4")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, cachedVideoAttachmentFile(context, messageId, 1, reference))
        assertTrue(
            shouldStartVideoAttachmentDownload(
                mine = false,
                videoAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = true,
            ),
        )
    }

    @Test
    fun emptyVideoFileIsNotTreatedAsCached() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-empty-cache"
        val reference = mediaReference(mediaType = "video/webm")
        File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-2-1.webm")
            .writeBytes(byteArrayOf())

        assertNull(cachedVideoAttachmentFile(context, messageId, 2, reference))
    }

    @Test
    fun plaintextCacheHitStartsMaterializationWhenAutoDownloadIsOff() {
        assertTrue(
            shouldStartVideoAttachmentDownload(
                mine = false,
                videoAutoDownload = false,
                hasCachedAttachment = true,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun uncachedIncomingVideoStillWaitsForUserOptInWhenAutoDownloadIsOff() {
        assertFalse(
            shouldStartVideoAttachmentDownload(
                mine = false,
                videoAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun invalidateVideoAttachmentCacheAfterPlaybackFailure_deletesDiskBeforeEvictingPlaintext() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-invalidate-success"
        val reference = mediaReference(mediaType = "video/mp4")
        val cached =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-6-1.mp4")
        cached.writeBytes(byteArrayOf(1, 2, 3))
        var evictCalled = false
        var diskGoneBeforeEvict = false

        runBlocking {
            invalidateVideoAttachmentCacheAfterPlaybackFailure(
                attachmentKey = AttachmentCachePublication.attachmentKey(messageId, 6, reference.sourceEpoch),
                file = cached,
            ) {
                diskGoneBeforeEvict = cachedVideoAttachmentFile(context, messageId, 6, reference) == null
                evictCalled = true
            }
        }

        assertTrue(evictCalled)
        assertTrue(diskGoneBeforeEvict)
        assertNull(cachedVideoAttachmentFile(context, messageId, 6, reference))
    }

    @Test
    fun invalidateVideoAttachmentCacheAfterPlaybackFailure_stillEvictsAndFailsWhenDeleteFails() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-invalidate-delete-fail"
        val reference = mediaReference(mediaType = "video/mp4")
        val blocked =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-7-1.mp4")
        blocked.mkdirs()
        File(blocked, "child").writeBytes(byteArrayOf(1))
        var evictCalled = false

        val failed =
            runBlocking {
                runCatching {
                    invalidateVideoAttachmentCacheAfterPlaybackFailure(
                        attachmentKey = AttachmentCachePublication.attachmentKey(messageId, 7, reference.sourceEpoch),
                        file = blocked,
                    ) {
                        evictCalled = true
                    }
                }
            }

        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull() is IOException)
        // A failed delete still evicts plaintext (and still surfaces the failure).
        assertTrue(evictCalled)
        assertTrue(blocked.exists())
    }

    @Test
    fun invalidateVideoAttachmentCacheAfterPlaybackFailure_completesEvictionWhenParentCoroutineCancelled() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-invalidate-noncancellable"
        val reference = mediaReference(mediaType = "video/mp4")
        val cached =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-8-1.mp4")
        cached.writeBytes(byteArrayOf(1, 2))
        val evictStarted = CompletableDeferred<Unit>()
        val evictContinue = CompletableDeferred<Unit>()
        var evictFinished = false

        runBlocking {
            val job =
                launch {
                    invalidateVideoAttachmentCacheAfterPlaybackFailure(
                        attachmentKey = AttachmentCachePublication.attachmentKey(messageId, 8, reference.sourceEpoch),
                        file = cached,
                    ) {
                        evictStarted.complete(Unit)
                        evictContinue.await()
                        evictFinished = true
                    }
                }
            evictStarted.await()
            job.cancel(CancellationException("composer disposed"))
            evictContinue.complete(Unit)
            job.join()
        }

        assertTrue(evictFinished)
        assertNull(cachedVideoAttachmentFile(context, messageId, 8, reference))
    }

    @Test
    fun quicktimeVideoUsesStableMovCacheSlot() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-quicktime-cache"
        val reference = mediaReference(mediaType = "video/quicktime")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-3-1.mov")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, cachedVideoAttachmentFile(context, messageId, 3, reference))
    }

    @Test
    fun sourceEpochIsPartOfVideoCacheDestinationIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-epoch-identity"
        val epochOne = mediaReference(mediaType = "video/mp4", sourceEpoch = 1uL)
        val epochTwo = mediaReference(mediaType = "video/mp4", sourceEpoch = 2uL)
        val epochOneFile =
            videoAttachmentCacheFileForTests(context, messageId, 1, epochOne)
        epochOneFile.parentFile?.mkdirs()
        epochOneFile.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(epochOneFile, cachedVideoAttachmentFile(context, messageId, 1, epochOne))
        assertNull(cachedVideoAttachmentFile(context, messageId, 1, epochTwo))
        assertEquals(
            File(epochOneFile.parentFile, "$messageId-1-2.mp4"),
            videoAttachmentCacheFileForTests(context, messageId, 1, epochTwo),
        )
    }

    @Test
    fun staleEpochInvalidationDoesNotDeleteNewerEpochCacheFile() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-stale-epoch-callback"
        val oldEpoch = mediaReference(mediaType = "video/mp4", sourceEpoch = 1uL)
        val newEpoch = mediaReference(mediaType = "video/mp4", sourceEpoch = 2uL)
        val staleFile = videoAttachmentCacheFileForTests(context, messageId, 4, oldEpoch)
        val currentFile = videoAttachmentCacheFileForTests(context, messageId, 4, newEpoch)
        staleFile.parentFile?.mkdirs()
        currentFile.writeBytes(byteArrayOf(4, 5, 6))

        runBlocking {
            invalidateVideoAttachmentCacheAfterPlaybackFailure(
                attachmentKey = AttachmentCachePublication.attachmentKey(messageId, 4, oldEpoch.sourceEpoch),
                file = staleFile,
            ) {}
        }

        assertFalse(staleFile.exists())
        assertEquals(currentFile, cachedVideoAttachmentFile(context, messageId, 4, newEpoch))
    }

    @Test
    fun videoMaterializationUsesSharedSingleFlight() {
        val source = mediaVideoSource().readText()

        assertTrue(
            "video materialization should use the shared single-flight utility",
            Regex(
                """private\s+val\s+videoMaterializations\s*=\s*SingleFlight<String,\s*java\.io\.File>\(\)""",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the flight must begin before the materializer checks the cache fast path",
            Regex(
                """videoMaterializations\.run\(file\.absolutePath\)\s*\{\s*materializeVideoAttachmentOnce\(""",
            ).containsMatchIn(source),
        )
    }

    @Test
    fun samePathWaiterAwaitsActiveMaterializationDespitePartialCacheFile() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val context = RuntimeEnvironment.getApplication()
                val messageId = "video-single-flight-waiter-${System.nanoTime()}"
                val attachmentIndex = 1
                val reference = mediaReference(mediaType = "video/mp4")
                val cacheFile =
                    videoAttachmentCacheFileForTests(context, messageId, attachmentIndex, reference)
                cacheFile.parentFile?.mkdirs()
                cacheFile.delete()

                val fullBytes = ByteArray(128) { (it + 1).toByte() }
                val partialBytes = fullBytes.copyOfRange(0, 16)
                val downloadEntered = CompletableDeferred<Unit>()
                val releaseDownload = CompletableDeferred<Unit>()

                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        materializeVideoAttachment(
                            context = context,
                            messageIdHex = messageId,
                            attachmentIndex = attachmentIndex,
                            reference = reference,
                            resolveBytes = {
                                downloadEntered.complete(Unit)
                                releaseDownload.await()
                                fullBytes
                            },
                        )
                    }
                try {
                    downloadEntered.await()

                    cacheFile.writeBytes(partialBytes)
                    assertEquals(partialBytes.size.toLong(), cacheFile.length())
                    assertEquals(cacheFile, cachedVideoAttachmentFile(context, messageId, attachmentIndex, reference))

                    val waiter =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            materializeVideoAttachment(
                                context = context,
                                messageIdHex = messageId,
                                attachmentIndex = attachmentIndex,
                                reference = reference,
                                resolveBytes = { error("waiter must join the owner's flight") },
                            )
                        }

                    assertFalse(
                        "same-path waiter must not return a partial cache file while materialization is in flight",
                        waiter.isCompleted,
                    )

                    releaseDownload.complete(Unit)
                    val ownerFile = owner.await()
                    val waiterFile = waiter.await()
                    assertArrayEquals(fullBytes, ownerFile.readBytes())
                    assertArrayEquals(fullBytes, waiterFile.readBytes())
                    assertEquals(fullBytes.size.toLong(), waiterFile.length())
                } finally {
                    releaseDownload.complete(Unit)
                    cacheFile.delete()
                }
            }
        }
    }

    @Test
    fun cachedPosterSkipsPosterFrameExtraction() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            ).firstOrNull { it.exists() }?.readText()
                ?: error("Missing MediaVideo.kt source file")
        val bubbleStart = source.indexOf("internal fun MediaVideoBubble(")
        val bubbleEnd = source.indexOf("@VisibleForTesting", bubbleStart)
        check(bubbleStart >= 0 && bubbleEnd > bubbleStart) {
            "Could not locate MediaVideoBubble boundaries in MediaVideo.kt; update this regression test"
        }
        val bubble = source.substring(bubbleStart, bubbleEnd)

        assertTrue(
            "MediaVideoBubble must decide whether it needs a poster before leaving the Compose thread",
            bubble.indexOf("val needsPoster = posterBitmap == null") in 0 until bubble.indexOf("withContext(Dispatchers.IO)"),
        )
        assertTrue(
            "a cached poster must bypass getScaledFrameAtTime while duration metadata is still read",
            Regex(
                """val frame\s*=\s*if \(needsPoster\) \{[\s\S]*?mmr\.getScaledFrameAtTime\([\s\S]*?\}\s*else\s*(?:\{\s*null\s*\}|null)""",
            ).containsMatchIn(bubble),
        )
    }

    private fun mediaReference(
        mediaType: String,
        sourceEpoch: ULong = 1uL,
    ): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "video",
            mediaType = mediaType,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )

    private fun mediaVideoSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaVideo.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MediaVideo.kt source file")
}
