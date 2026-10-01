package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.MAX_ANIMATED_PROFILE_AVATAR_EDGE
import dev.ipf.whitenoise.android.core.twoFrameGif
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** The own-profile picture keeps a valid GIF animated while every other input keeps the JPEG path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePictureDraftProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A GIF stays a GIF: both frames and the loop survive, private metadata does not. */
    @Test
    fun gifIsKeptAnimatedWithMetadataStripped() {
        val draft = ProfilePictureDraftProcessor.fromBytes(twoFrameGif(4, 3, comment = SECRET), SOURCE_URL)

        assertEquals("image/gif", draft.mediaType)
        assertTrue(isGif(draft.plaintext))
        assertEquals(2, draft.plaintext.count { it == GIF_IMAGE_DESCRIPTOR })
        assertTrue(draft.plaintext.containsAscii("NETSCAPE2.0"))
        assertFalse(draft.plaintext.containsAscii(SECRET))
        assertEquals("4x3", draft.dim)
        assertNotNull(draft.thumbhash)
        assertEquals(SOURCE_URL, draft.sourceUrl)
    }

    /** Bytes over the profile ceiling fail as too large rather than being flattened. */
    @Test
    fun oversizedGifIsRejected() {
        val oversized = twoFrameGif() + ByteArray(REMOTE_PROFILE_IMAGE_MAX_BYTES)

        assertFailsWith<ImageUploadPreparationException.PreparedImageTooLarge> {
            ProfilePictureDraftProcessor.fromBytes(oversized, null)
        }
    }

    /** A canvas larger than the app will animate fails as too large rather than being flattened. */
    @Test
    fun oversizedGifCanvasIsRejected() {
        assertFailsWith<ImageUploadPreparationException.PreparedImageTooLarge> {
            ProfilePictureDraftProcessor.fromBytes(twoFrameGif(MAX_ANIMATED_PROFILE_AVATAR_EDGE + 1, 1), null)
        }
    }

    /** A truncated GIF fails as unsupported instead of reaching MDK or the JPEG path. */
    @Test
    fun truncatedGifIsUnsupported() {
        assertFailsWith<ImageUploadPreparationException.UnsupportedImage> {
            ProfilePictureDraftProcessor.fromBytes(twoFrameGif().copyOf(GIF_HEADER_ONLY_BYTES), null)
        }
    }

    /** A PNG still becomes the same metadata-free JPEG it always did. */
    @Test
    fun staticImagesKeepTheJpegPath() {
        val png = pngBytes()

        val draft = ProfilePictureDraftProcessor.fromBytes(png, SOURCE_URL)

        assertEquals(MediaPipeline.RECOMPRESSED_MIME, draft.mediaType)
        assertEquals(GroupImageDraftProcessor.fromBytes(png, SOURCE_URL), draft)
    }

    /** Group images are unchanged: the group processor still flattens a GIF to JPEG. */
    @Test
    fun groupImagesStillFlattenGifs() {
        val draft = GroupImageDraftProcessor.fromBytes(twoFrameGif(4, 4), null)

        assertEquals(MediaPipeline.RECOMPRESSED_MIME, draft.mediaType)
    }

    /** A photo-picker GIF is sniffed by content and uploaded intact; a PNG pick is not treated as one. */
    @Test
    fun pickedGifIsReadBoundedAndKept() =
        runBlocking {
            val gif = uriOf("pick.png", twoFrameGif(2, 2))
            val png = uriOf("pick.gif", pngBytes())

            assertTrue(ProfilePictureDraftProcessor.isGifSource(context.contentResolver, gif))
            assertFalse(ProfilePictureDraftProcessor.isGifSource(context.contentResolver, png))
            assertEquals(
                "image/gif",
                ProfilePictureDraftProcessor.fromContentUri(context.contentResolver, gif).mediaType,
            )
        }

    /** A picked GIF larger than the ceiling stops at the bound and fails as too large. */
    @Test
    fun pickedOversizedGifIsRejectedAtTheReadBound() {
        val uri = uriOf("huge.gif", twoFrameGif() + ByteArray(REMOTE_PROFILE_IMAGE_MAX_BYTES))

        assertFailsWith<ImageUploadPreparationException.PreparedImageTooLarge> {
            runBlocking { ProfilePictureDraftProcessor.fromContentUri(context.contentResolver, uri) }
        }
    }

    /** A missing provider file is not a GIF and fails through the existing unsupported path. */
    @Test
    fun unreadablePickIsNotAGif() =
        runBlocking {
            val missing = Uri.fromFile(File(context.cacheDir, "missing.gif"))

            assertFalse(ProfilePictureDraftProcessor.isGifSource(context.contentResolver, missing))
        }

    /** Writes [bytes] to a cache file and returns its `file://` URI. */
    private fun uriOf(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(bytes) })

    /** A small opaque PNG. */
    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** True when [text] occurs anywhere in these bytes. */
    private fun ByteArray.containsAscii(text: String): Boolean = String(this, Charsets.ISO_8859_1).contains(text)

    /** Asserts [block] throws exactly [T]. */
    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("expected ${T::class.simpleName} but was $thrown", thrown is T)
    }

    private companion object {
        const val SOURCE_URL = "https://images.example/party.gif"
        const val SECRET = "private comment"
        const val GIF_IMAGE_DESCRIPTOR: Byte = 0x2c
        const val GIF_HEADER_ONLY_BYTES = 19
    }
}
