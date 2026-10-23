package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
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
@dev.ipf.whitenoise.android.media.sandbox.MediaContainmentAcceptance
@SdkSuppress(minSdkVersion = 30)
class AnimationSourceAdmissionDeviceTest {
    @Test
    fun admittedGifDecodesThroughTheNativeAnimatedDecoder() {
        assertTrue(admitAnimationSource(ordinaryGif()) is AnimationSourceAdmission.Admitted)
        assertIsolatedAnimation(ordinaryGif())
    }

    @Test
    fun advertisedStillMimeCannotSuppressAnAdmittedAnimation() {
        assertAnimatedPresentation(decode(ordinaryGif(), "image/png"))
    }

    @Test
    fun platformEncodedWebpFramesAnimateAndCannotBypassAdmissionWithAStillMime() {
        val source = ordinaryAnimatedWebp()
        assertTrue(admitAnimationSource(source) is AnimationSourceAdmission.Admitted)
        assertIsolatedAnimation(source)
        assertAnimatedPresentation(decode(source, "image/png"))
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

    @Test
    fun isolatedFramesChangePixelsAndPauseResumeWithoutBlockingStillImages() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .install(instrumentation.targetContext)
        for (source in listOf(ordinaryGif(), ordinaryAnimatedWebp())) {
            val animated = requireNotNull(MediaPipeline.decodeAnimatedDrawable(source))
            val pixels = Bitmap.createBitmap(animated.intrinsicWidth, animated.intrinsicHeight, Bitmap.Config.ARGB_8888)
            try {
                val worker = requireNotNull(dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder.lastIsolation)
                assertTrue(worker.isolated && !worker.internetGranted && !worker.runtimeInitialized)
                assertTrue(worker.uid != android.os.Process.myUid())
                instrumentation.runOnMainSync { (animated as Animatable).start() }
                assertChangingPixels(animated, pixels)
                instrumentation.runOnMainSync { (animated as Animatable).stop() }
                var stoppedFrame = 0L
                var stopped = 0
                instrumentation.runOnMainSync {
                    stoppedFrame =
                        (animated as dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable).presentedFrame
                    animated.draw(android.graphics.Canvas(pixels))
                    stopped = pixels.getPixel(0, 0)
                }
                Thread.sleep(250L)
                instrumentation.runOnMainSync {
                    animated.draw(android.graphics.Canvas(pixels))
                    assertEquals(
                        stoppedFrame,
                        (animated as dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable).presentedFrame,
                    )
                    assertEquals(stopped, pixels.getPixel(0, 0))
                }
                // Still-image requests retain their separate lane while this animation is paused.
                val encoded = ByteArrayOutputStream().also { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val png = encoded.toByteArray()
                requireNotNull(MediaPipeline.decodeSampledBitmap(png, 2)).recycle()
                instrumentation.runOnMainSync {
                    (animated as Animatable).start()
                    assertTrue(animated.isRunning)
                }
                assertChangingPixels(animated, pixels)
            } finally {
                instrumentation.runOnMainSync { (animated as java.io.Closeable).close() }
                pixels.recycle()
            }
        }
    }

    /** The exact inline decoder entry point must deliver changing isolated pixels within the smaller row budget. */
    @Test
    fun inlineGrantStreamsChangingIsolatedPixels() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .install(instrumentation.targetContext)
        assertTrue(
            "Previous isolated animation must finish retirement before the decorative fixture starts",
            dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationSession
                .awaitIdleForTest(),
        )
        val animated =
            requireNotNull(
                runBlocking {
                    dev.ipf.whitenoise.android.ui.conversation.media
                        .decodeInlineAttachmentAnimation(ordinaryGif())
                },
            )
        val pixels = Bitmap.createBitmap(animated.intrinsicWidth, animated.intrinsicHeight, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(animated is IsolatedAnimationDrawable)
            val isolation = requireNotNull(dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder.lastIsolation)
            assertTrue(isolation.isolated && !isolation.internetGranted && !isolation.runtimeInitialized)
            assertTrue(isolation.uid != android.os.Process.myUid())
            assertTrue(
                maxOf(animated.intrinsicWidth, animated.intrinsicHeight) <=
                    dev.ipf.whitenoise.android.ui.conversation.media.INLINE_ANIMATION_MAX_EDGE_PX,
            )
            instrumentation.runOnMainSync { (animated as Animatable).start() }
            assertChangingPixels(animated, pixels)
        } finally {
            instrumentation.runOnMainSync { (animated as java.io.Closeable).close() }
            pixels.recycle()
        }
    }

    private fun assertChangingPixels(
        animated: Drawable,
        pixels: Bitmap,
    ) {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        val colors = HashSet<Int>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000L
        while (colors.size < 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                animated.setBounds(0, 0, pixels.width, pixels.height)
                animated.draw(android.graphics.Canvas(pixels))
                colors.add(pixels.getPixel(0, 0))
            }
            Thread.sleep(25L)
        }
        assertTrue("Playing animation must deliver changing isolated pixels, not just set isRunning", colors.size >= 2)
    }

    @Test
    fun accountResetRejectsOldAnimationFramesAndAllowsANewGeneration() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .install(instrumentation.targetContext)
        val old = requireNotNull(MediaPipeline.decodeAnimatedDrawable(ordinaryGif()))
        val oldAnimation = old as IsolatedAnimationDrawable
        val pixels = Bitmap.createBitmap(old.intrinsicWidth, old.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val oldUid = requireNotNull(dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder.lastIsolation).uid
        var retiredFrame = 0L
        var retiredPixel = 0
        try {
            instrumentation.runOnMainSync {
                (old as Animatable).start()
                dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
                    .resetForAccount()
                retiredFrame = oldAnimation.presentedFrame
                old.setBounds(0, 0, pixels.width, pixels.height)
                old.draw(android.graphics.Canvas(pixels))
                retiredPixel = pixels.getPixel(0, 0)
            }
            val replacement = requireNotNull(MediaPipeline.decodeAnimatedDrawable(ordinaryGif()))
            try {
                val current =
                    requireNotNull(
                        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder.lastIsolation,
                    )
                assertTrue(oldUid != current.uid)
                instrumentation.runOnMainSync {
                    assertEquals(retiredFrame, oldAnimation.presentedFrame)
                    pixels.eraseColor(Color.TRANSPARENT)
                    old.draw(android.graphics.Canvas(pixels))
                    assertEquals(retiredPixel, pixels.getPixel(0, 0))
                }
            } finally {
                instrumentation.runOnMainSync { (replacement as java.io.Closeable).close() }
            }
        } finally {
            instrumentation.runOnMainSync { (old as java.io.Closeable).close() }
            pixels.recycle()
        }
    }

    private fun assertIsolatedAnimation(source: ByteArray) {
        val decoded = requireNotNull(MediaPipeline.decodeAnimatedDrawable(source))
        try {
            assertTrue(decoded is dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable)
        } finally {
            (decoded as java.io.Closeable).close()
        }
    }

    private fun assertAnimatedPresentation(presentation: DecodedAttachmentPresentation?) {
        assertTrue(presentation is DecodedAttachmentPresentation.Animated)
        ((presentation as DecodedAttachmentPresentation.Animated).drawable as java.io.Closeable).close()
    }

    private fun decode(
        bytes: ByteArray,
        mediaType: String,
    ): DecodedAttachmentPresentation? =
        runBlocking {
            decodeMessageAttachmentImage(bytes, mediaType, MediaPipeline.THUMBNAIL_MAX_EDGE_PX, animationAllowed = true)
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
        assertEquals(encoded.size.toLong(), 8L + u32le(encoded, 4))
        return encodedLosslessChunk(encoded)
    }

    /** The platform may wrap its VP8L chunk in VP8X plus colour/metadata chunks. */
    private fun encodedLosslessChunk(encoded: ByteArray): ByteArray {
        var offset = 12
        var losslessChunk: ByteArray? = null
        while (offset < encoded.size) {
            assertTrue(offset <= encoded.size - 8)
            val tag = String(encoded, offset, 4, Charsets.US_ASCII)
            val size = u32le(encoded, offset + 4)
            val end = offset.toLong() + 8L + size + (size and 1L)
            assertTrue(end <= encoded.size.toLong())
            if (tag == "VP8L") {
                assertNull(losslessChunk)
                // Keep the encoder's entire compressed chunk, including padding.
                losslessChunk = encoded.copyOfRange(offset, end.toInt())
            } else {
                assertTrue(tag in setOf("VP8X", "ICCP", "EXIF", "XMP "))
            }
            offset = end.toInt()
        }
        assertTrue(losslessChunk != null)
        return requireNotNull(losslessChunk)
    }

    private fun webpChunk(
        tag: String,
        payload: ByteArray,
    ): ByteArray =
        tag.encodeToByteArray() + littleEndianSize(payload.size) + payload +
            if (payload.size and 1 == 1) byteArrayOf(0) else byteArrayOf()

    private fun littleEndianSize(value: Int): ByteArray = ByteArray(4) { index -> (value ushr (8 * index)).toByte() }
}
