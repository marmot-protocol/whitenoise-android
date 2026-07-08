package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVideoAttachmentFile
import dev.ipf.whitenoise.android.ui.conversation.media.shouldStartVideoAttachmentDownload
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
class VideoAttachmentCacheStateTest {
    @Test
    fun cachedVideoFileIsFoundOnEntry() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-cache-hit"
        val reference = mediaReference(mediaType = "video/mp4")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-1.mp4")
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
        File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-2.webm")
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
    fun quicktimeVideoUsesStableMovCacheSlot() {
        val context = RuntimeEnvironment.getApplication()
        val messageId = "video-quicktime-cache"
        val reference = mediaReference(mediaType = "video/quicktime")
        val expected =
            File(File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }, "$messageId-3.mov")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, cachedVideoAttachmentFile(context, messageId, 3, reference))
    }

    private fun mediaReference(mediaType: String): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "",
            plaintextSha256 = "",
            nonceHex = "",
            fileName = "video",
            mediaType = mediaType,
            version = "1",
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
}
