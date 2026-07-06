package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.MediaCacheDirs
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceAttachmentCacheStateTest {
    @Test
    fun cachedVoiceFileIsFoundOnEntry() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "voice-cache-hit"
        val reference = mediaReference(mediaType = "audio/mp4")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-1.m4a")
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
        File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-2.aac")
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
            File(File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }, "$messageId-3.m4a")
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

    private fun mediaReference(mediaType: String): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "voice",
            mediaType = mediaType,
            version = "1",
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
}
