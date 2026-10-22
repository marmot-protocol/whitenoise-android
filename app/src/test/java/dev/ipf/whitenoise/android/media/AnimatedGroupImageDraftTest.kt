package dev.ipf.whitenoise.android.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.twoFrameGif
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Both encrypted creation and public group-avatar upload receive sanitized animated GIF bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedGroupImageDraftTest {
    @Test
    fun groupGifRetainsFramesAndLoopButDropsMetadataAndLegacyOrigin() {
        val source = twoFrameGif(4, 3, comment = "private-location")
        val draft = GroupImageDraftProcessor.fromGroupBytes(source, "https://example.com/image.gif")
        assertEquals("image/gif", draft.mediaType)
        assertEquals("4x3", draft.dim)
        assertNotNull(draft.thumbhash)
        assertEquals(2, draft.plaintext.count { it == 0x2c.toByte() })
        assertTrue(draft.plaintext.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        assertFalse(draft.plaintext.toString(Charsets.ISO_8859_1).contains("private-location"))
        assertNull(draft.initialGroupImage().sourceUrl)
        assertEquals("image/gif", draft.initialGroupImage().mediaType)
    }

    @Test
    fun groupDocumentGifUsesSamePreparationAsRemoteBytes() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val file = File.createTempFile("group-avatar", ".gif", context.cacheDir)
            try {
                val bytes = twoFrameGif(4, 3)
                file.writeBytes(bytes)
                val draft = GroupImageDraftProcessor.fromGroupContentUri(context.contentResolver, Uri.fromFile(file))
                assertEquals(GroupImageDraftProcessor.fromGroupBytes(bytes, null), draft)
            } finally {
                file.delete()
            }
        }

    @Test(expected = ImageUploadPreparationException.PreparedImageTooLarge::class)
    fun oversizedGifNeverFallsBackToJpeg() {
        GroupImageDraftProcessor.fromGroupBytes(twoFrameGif() + ByteArray(REMOTE_PROFILE_IMAGE_MAX_BYTES), null)
    }

    @Test(expected = ImageUploadPreparationException.UnsupportedImage::class)
    fun malformedGifNeverFallsBackToJpeg() {
        GroupImageDraftProcessor.fromGroupBytes("GIF89a".toByteArray(), null)
    }
}
