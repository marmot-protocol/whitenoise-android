package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Which pixels actually reach the upload.
 *
 * The geometry is pinned elsewhere; what these add is that the published bytes contain the region
 * the crop named and nothing else. A source split into two flat colours makes that checkable: a
 * crop over one half must decode to that half's colour, so a selection silently taken from the
 * centre, or one stretched across both halves, shows up as the wrong pixel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdentityImageCropRenderTest {
    /** A crop panned fully left publishes the left half's pixels. */
    @Test
    fun aCropPannedLeftPublishesTheLeftHalf() =
        runTest {
            val draft = render(IdentityImageCrop(focusX = 0f))

            assertDominantlyRed(draft)
        }

    /** A crop panned fully right publishes the right half's pixels. */
    @Test
    fun aCropPannedRightPublishesTheRightHalf() =
        runTest {
            val draft = render(IdentityImageCrop(focusX = 1f))

            assertDominantlyBlue(draft)
        }

    /** The published image is square, whatever the source's shape. */
    @Test
    fun thePublishedImageIsSquare() =
        runTest {
            val draft = render(IdentityImageCrop.Centered)
            val decoded = decode(draft)

            assertEquals("a published identity image must be square", decoded.width, decoded.height)
        }

    /** The draft reports the dimensions of the bytes it carries and claims no source URL. */
    @Test
    fun theDraftDescribesItsOwnBytes() =
        runTest {
            val draft = render(IdentityImageCrop.Centered)
            val decoded = decode(draft)

            assertEquals("${decoded.width}x${decoded.height}", draft.dim)
            assertEquals("a cropped identity image is published from bytes, not a URL", null, draft.sourceUrl)
            assertTrue("the published bytes are not empty", draft.plaintext.isNotEmpty())
        }

    /** A source that cannot be decoded fails as an unsupported image rather than publishing noise. */
    @Test
    fun anUndecodableSourceFailsClosed() =
        runTest {
            assertThrows(ImageUploadPreparationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    renderIdentityImageDraft(ByteArray(64) { 7 }, IdentityImageCrop.Centered, MediaQuality.Standard)
                }
            }
        }

    private suspend fun render(crop: IdentityImageCrop): ImageUploadDraft {
        val source = splitColorSource()
        return renderIdentityImageDraft(source, crop, MediaQuality.Standard)
    }

    private fun decode(draft: ImageUploadDraft): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(draft.plaintext, 0, draft.plaintext.size)) {
            "published identity bytes must decode"
        }

    private fun assertDominantlyRed(draft: ImageUploadDraft) {
        sampledPixels(draft).forEach { (label, pixel) ->
            assertTrue(
                "expected the left half's red across the image, got ${Integer.toHexString(pixel)} at $label",
                Color.red(pixel) > Color.blue(pixel) + CHANNEL_MARGIN,
            )
        }
    }

    private fun assertDominantlyBlue(draft: ImageUploadDraft) {
        sampledPixels(draft).forEach { (label, pixel) ->
            assertTrue(
                "expected the right half's blue across the image, got ${Integer.toHexString(pixel)} at $label",
                Color.blue(pixel) > Color.red(pixel) + CHANNEL_MARGIN,
            )
        }
    }

    /**
     * Samples across the width rather than one pixel in the middle.
     *
     * A crop that straddles the source's colour boundary still puts one colour under the centre, so
     * a single sample would pass for a selection that was never moved.
     */
    private fun sampledPixels(draft: ImageUploadDraft): List<Pair<String, Int>> {
        val decoded = decode(draft)
        val y = decoded.height / 2
        return listOf(
            "left" to decoded.getPixel(decoded.width / SAMPLE_INSET, y),
            "centre" to decoded.getPixel(decoded.width / 2, y),
            "right" to decoded.getPixel(decoded.width - decoded.width / SAMPLE_INSET - 1, y),
        )
    }

    /** A landscape source whose left half is red and right half blue. */
    private fun splitColorSource(): ByteArray {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        for (x in 0 until SOURCE_WIDTH) {
            val color = if (x < SOURCE_WIDTH / 2) Color.RED else Color.BLUE
            for (y in 0 until SOURCE_HEIGHT) {
                bitmap.setPixel(x, y, color)
            }
        }
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
            output.toByteArray()
        }
    }

    private companion object {
        const val SOURCE_WIDTH = 120
        const val SOURCE_HEIGHT = 60
        const val PNG_QUALITY = 100
        const val CHANNEL_MARGIN = 40
        const val SAMPLE_INSET = 8
    }
}
