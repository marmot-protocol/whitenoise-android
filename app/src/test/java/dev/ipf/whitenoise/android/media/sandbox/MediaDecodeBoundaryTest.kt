package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.whitenoise.android.media.MediaPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaDecodeBoundaryTest {
    @Test
    fun unavailableExifDoesNotAuthorizeMetadataStrippingAndCanRetry() {
        val source = png()
        val gif =
            java.util.Base64
                .getDecoder()
                .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        var orientation: Int? = null
        var downstreamCalls = 0
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun exif(bytes: ByteArray): Int? = orientation

                override fun inspect(bytes: ByteArray): ImageInspection? {
                    downstreamCalls += 1
                    return null
                }

                override fun decode(
                    bytes: ByteArray,
                    edge: Int,
                    oriented: Boolean,
                    operation: Int,
                ): Bitmap? {
                    downstreamCalls += 1
                    return null
                }
            },
        )
        try {
            assertNull(MediaPipeline.readExifOrientation(source))
            val unavailable = MediaPipeline.prepareOriginalImageForUpload(source, "photo.png")
            assertEquals(MediaPipeline.OriginalImageReadResult.Failed, unavailable)
            assertNull(MediaPipeline.sanitizeAnimatedImageMetadata(gif))
            orientation = MediaPipeline.EXIF_ORIENTATION_ROTATE_90
            val rotated = MediaPipeline.prepareOriginalImageForUpload(source, "photo.png")
            assertEquals(MediaPipeline.OriginalImageReadResult.Unsupported, rotated)
            assertNull(MediaPipeline.sanitizeAnimatedImageMetadata(gif))
            assertEquals(0, downstreamCalls)
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            val recovered = MediaPipeline.prepareOriginalImageForUpload(source, "photo.png")
            assertTrue(recovered is MediaPipeline.OriginalImageReadResult.Success)
        } finally {
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }

    @Test
    fun missingTransportFailsClosedWithoutCallingLocalCodec() {
        IsolatedMediaDecoder.installTestTransport(null)
        assertNull(IsolatedMediaDecoder.decode(png(), 128))
    }

    @Test
    fun explicitFixturePreservesPixelsAndBounds() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        val result = requireNotNull(IsolatedMediaDecoder.decode(png(), 1))
        try {
            assertEquals(1, result.width)
            assertEquals(Color.RED, result.getPixel(0, 0))
        } finally {
            result.recycle()
        }
    }

    @Test
    fun malformedBytesAndInvalidTargetsStayUnavailable() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        assertNull(IsolatedMediaDecoder.decode(byteArrayOf(1, 2, 3), 128))
        assertNull(IsolatedMediaDecoder.decode(png(), Int.MAX_VALUE))
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
