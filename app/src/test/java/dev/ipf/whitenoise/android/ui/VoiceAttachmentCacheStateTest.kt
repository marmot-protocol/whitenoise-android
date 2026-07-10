package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVoiceAttachmentFile
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.shouldInvalidateVoiceAttachmentCache
import dev.ipf.whitenoise.android.ui.conversation.media.shouldStartVoiceAttachmentDownload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAttachmentCacheStateTest {
    private companion object {
        private const val TEST_HANG_GUARD_MS = 30_000L
    }

    @Test
    fun cachedVoiceFileIsFoundOnEntry() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-cache-hit"
        val reference = mediaReference(mediaType = "audio/mp4")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-1-1.m4a")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, cachedVoiceAttachmentFile(context, messageId, 1, reference))
        assertTrue(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = true,
            ),
        )
    }

    @Test
    fun emptyVoiceFileIsNotTreatedAsCached() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-empty-cache"
        val reference = mediaReference(mediaType = "audio/aac")
        File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-2-1.aac")
            .writeBytes(byteArrayOf())

        assertNull(cachedVoiceAttachmentFile(context, messageId, 2, reference))
    }

    @Test
    fun plaintextCacheHitStartsMaterializationWhenAutoDownloadIsOff() {
        assertTrue(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = true,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun uncachedIncomingClipStillWaitsForUserOptInWhenAutoDownloadIsOff() {
        assertFalse(
            shouldStartVoiceAttachmentDownload(
                mine = false,
                audioAutoDownload = false,
                hasCachedAttachment = false,
                hasCachedFile = false,
            ),
        )
    }

    @Test
    fun playbackPrepareFailureInvalidatesSeededCachedVoiceFile() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-corrupt-cache"
        val reference = mediaReference(mediaType = "audio/mp4")
        val cached =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-3-1.m4a")
        cached.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(cached, cachedVoiceAttachmentFile(context, messageId, 3, reference))
        assertTrue(
            shouldInvalidateVoiceAttachmentCache(
                VoicePlaybackController.PlaybackStartResult.PrepareFailed,
            ),
        )
        cached.delete()
        assertNull(cachedVoiceAttachmentFile(context, messageId, 3, reference))
    }

    @Test
    fun focusDenialDoesNotInvalidateCachedVoiceFile() {
        assertFalse(
            shouldInvalidateVoiceAttachmentCache(
                VoicePlaybackController.PlaybackStartResult.FocusDenied,
            ),
        )
    }

    @Test
    fun sourceEpochIsPartOfVoiceCacheDestinationIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-epoch-identity"
        val epochOne = mediaReference(mediaType = "audio/mp4", sourceEpoch = 1uL)
        val epochTwo = mediaReference(mediaType = "audio/mp4", sourceEpoch = 2uL)
        val epochOneFile =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-1-1.m4a")
        epochOneFile.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(epochOneFile, cachedVoiceAttachmentFile(context, messageId, 1, epochOne))
        assertNull(cachedVoiceAttachmentFile(context, messageId, 1, epochTwo))
    }

    @Test
    fun samePathWaiterAwaitsActiveMaterializationDespitePartialCacheFile() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val context = RuntimeEnvironment.getApplication()
                val messageId = "voice-single-flight-waiter-${System.nanoTime()}"
                val attachmentIndex = 1
                val reference = mediaReference(mediaType = "audio/mp4")
                val cacheFile =
                    File(
                        File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() },
                        "$messageId-$attachmentIndex-${reference.sourceEpoch}.m4a",
                    )
                cacheFile.delete()

                val fullBytes = ByteArray(128) { (it + 1).toByte() }
                val partialBytes = fullBytes.copyOfRange(0, 16)
                val downloadEntered = CompletableDeferred<Unit>()
                val releaseDownload = CompletableDeferred<Unit>()

                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        materializeVoiceAttachment(
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
                    assertEquals(cacheFile, cachedVoiceAttachmentFile(context, messageId, attachmentIndex, reference))

                    val waiter =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            materializeVoiceAttachment(
                                context = context,
                                messageIdHex = messageId,
                                attachmentIndex = attachmentIndex,
                                reference = reference,
                                resolveBytes = { error("waiter must join the owner's flight") },
                            )
                        }

                    val earlyWaiterFile = withTimeoutOrNull(100) { waiter.await() }

                    releaseDownload.complete(Unit)
                    val ownerFile = owner.await()

                    if (earlyWaiterFile != null) {
                        fail(
                            "same-path waiter must not return a partial cache file while materialization is in flight",
                        )
                    }

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

    private fun mediaReference(
        mediaType: String,
        sourceEpoch: ULong = 1uL,
    ): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "voice",
            mediaType = mediaType,
            version = "1",
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )
}
