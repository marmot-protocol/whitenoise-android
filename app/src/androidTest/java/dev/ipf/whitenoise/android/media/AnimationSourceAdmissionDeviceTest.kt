package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.conversation.media.DecodedAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.decodeMessageAttachmentImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Real-codec check of the animation admission chokepoint (qualify on API 36).
 *
 * Only a tiny, ordinary, hand-built GIF is ever decoded. Each refused fixture is
 * that same harmless GIF with one admission-relevant change (a byte after the
 * trailer, or a 4097 px logical screen around 1 px frames). Decoder-spy JVM tests
 * prove refusal skips both decoders; null alone is not proof of skipped decoding.
 *
 * The WebP frames come from the platform's lossless encoder over our own 2 x 2
 * solid-colour bitmaps. Only their animation container is assembled here, so no
 * invented compressed bitstream or external image/provenance is trusted.
 */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@SdkSuppress(minSdkVersion = 30)
class AnimationSourceAdmissionDeviceTest {
    @Test
    fun admittedGifDecodesThroughTheNativeAnimatedDecoder() {
        assertTrue(admitAnimationSource(ordinaryGif()) is AnimationSourceAdmission.Admitted)
        assertTrue(MediaPipeline.decodeAnimatedDrawable(ordinaryGif()) is AnimatedImageDrawable)
    }

    @Test
    fun advertisedStillMimeCannotSuppressAnAdmittedAnimation() {
        assertTrue(decode(ordinaryGif(), "image/png") is DecodedAttachmentPresentation.Animated)
    }

    @Test
    fun platformEncodedWebpFramesAnimateAndCannotBypassAdmissionWithAStillMime() {
        val source = ordinaryAnimatedWebp()
        assertTrue(admitAnimationSource(source) is AnimationSourceAdmission.Admitted)
        assertTrue(MediaPipeline.decodeAnimatedDrawable(source) is AnimatedImageDrawable)
        assertTrue(decode(source, "image/png") is DecodedAttachmentPresentation.Animated)
        val oversized = source.copyOf()
        // VP8X canvas width minus one, little-endian at byte 24.
        oversized[24] = 0
        oversized[25] = 0x10
        oversized[26] = 0
        assertTrue(admitAnimationSource(oversized) is AnimationSourceAdmission.Refused)
        assertNull(MediaPipeline.decodeAnimatedDrawable(oversized))
        assertNull(decode(oversized, "image/png"))
    }

    @Test
    fun refusedGifReachesNeitherNativeDecoderWhateverItsMime() {
        val trailingByte = ordinaryGif() + byteArrayOf(0)
        val wideCanvas = ordinaryGif(screenWidth = MAX_ANIMATION_SOURCE_EDGE_PX + 1)
        for (refused in listOf(trailingByte, wideCanvas)) {
            assertTrue(admitAnimationSource(refused) is AnimationSourceAdmission.Refused)
            assertNull(MediaPipeline.decodeAnimatedDrawable(refused))
            for (mediaType in listOf("image/gif", "image/png", "application/octet-stream")) {
                assertNull(decode(refused, mediaType))
            }
        }
    }

    @Test
    fun ordinaryStaticImagesStillUseTheSampledPath() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val png =
            ByteArrayOutputStream().use { out ->
                source.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        source.recycle()
        assertEquals(AnimationSourceAdmission.NotAnimation, admitAnimationSource(png))
        val decoded = decode(png, "image/gif")
        assertTrue(decoded is DecodedAttachmentPresentation.Static)
        (decoded as DecodedAttachmentPresentation.Static).bitmap.recycle()
    }

    private fun decode(
        bytes: ByteArray,
        mediaType: String,
    ): DecodedAttachmentPresentation? =
        runBlocking {
            decodeMessageAttachmentImage(bytes, mediaType, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
        }

    /**
     * A looping two-frame GIF: a [screenWidth] x 1 logical screen, a two-colour
     * global palette and two 1 x 1 frames at the origin with 100 ms delays. Each
     * frame's LZW stream is clear (4), one colour index, end (5) at code size 2.
     */
    private fun ordinaryGif(screenWidth: Int = 1): ByteArray {
        val width = byteArrayOf((screenWidth and 0xff).toByte(), (screenWidth shr 8).toByte())
        val screen = "GIF89a".encodeToByteArray() + width + byteArrayOf(1, 0, 0x80.toByte(), 0, 0)
        val palette = byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0xff.toByte())
        val loop =
            byteArrayOf(0x21, 0xff.toByte(), 0x0b) + "NETSCAPE2.0".encodeToByteArray() + byteArrayOf(3, 1, 0, 0, 0)
        return screen + palette + loop + frame(colorIndex = 0) + frame(colorIndex = 1) + byteArrayOf(0x3b)
    }

    private fun frame(colorIndex: Int): ByteArray {
        val control = byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0)
        val descriptor = byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0)
        val packed = 4 or (colorIndex shl 3) or (5 shl 6)
        return control + descriptor + byteArrayOf(2, 2, (packed and 0xff).toByte(), (packed shr 8).toByte(), 0)
    }

    private fun ordinaryAnimatedWebp(): ByteArray {
        val canvas = byteArrayOf(2, 0, 0, 0, 1, 0, 0, 1, 0, 0)
        val rectangle = byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 100, 0, 0, 0)
        val body =
            "WEBP".encodeToByteArray() +
                webpChunk("VP8X", canvas) + webpChunk("ANIM", ByteArray(6)) +
                webpChunk("ANMF", rectangle + encodedWebpFrame(Color.RED)) +
                webpChunk("ANMF", rectangle + encodedWebpFrame(Color.BLUE))
        return "RIFF".encodeToByteArray() + littleEndianSize(body.size) + body
    }

    private fun encodedWebpFrame(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val encoded =
            try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { out ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out))
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        assertEquals("RIFF", String(encoded, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(encoded, 8, 4, Charsets.US_ASCII))
        assertEquals("VP8L", String(encoded, 12, 4, Charsets.US_ASCII))
        // Preserve the platform's complete compressed chunk including padding.
        val payloadSize = u32le(encoded, 16)
        assertEquals(encoded.size.toLong(), 20L + payloadSize + (payloadSize and 1L))
        return encoded.copyOfRange(12, encoded.size)
    }

    private fun webpChunk(tag: String, payload: ByteArray): ByteArray =
        tag.encodeToByteArray() + littleEndianSize(payload.size) + payload +
            if (payload.size and 1 == 1) byteArrayOf(0) else byteArrayOf()

    private fun littleEndianSize(value: Int): ByteArray = ByteArray(4) { index -> (value ushr (8 * index)).toByte() }
}
