package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Harmless valid encodings and bounded mutations, not exploit samples or user photographs. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class MediaCodecCorpusDeviceTest {
    @Test
    fun malformedJpegPngExifAndSvgStayContainedAndHealthyPixelsRecover() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val caller = Process.myPid()
        val jpeg = image(Bitmap.CompressFormat.JPEG)
        val png = image(Bitmap.CompressFormat.PNG)
        val marker = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x7f, 0xff)
        val app1 = marker + "Exif\u0000\u0000II".toByteArray()
        val corpus =
            listOf(
                jpeg.copyOf(8),
                jpeg.copyOf(jpeg.size / 2),
                app1,
                png.copyOf(20),
                png.copyOf(png.size / 2),
                png.copyOf().also { it[12] = 0x7f },
                "<svg><path d='M 0 ???'/></svg>".toByteArray(),
                "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///not-authorized'>]><svg>&x;</svg>".toByteArray(),
            )
        for ((index, bytes) in corpus.withIndex()) {
            val decoded = if (index >= 6) IsolatedMediaDecoder.svg(bytes) else IsolatedMediaDecoder.decode(bytes, 32)
            // Native codecs may salvage truncated images. Containment, output bounds and recovery
            // are the guarantee, rather than claiming every decoder rejects every mutation.
            val limit = if (index >= 6) MediaDecodePolicy.SVG_EDGE else 32
            decoded?.let {
                assertTrue(it.width in 1..limit && it.height in 1..limit)
                it.recycle()
            }
            if (index == 2) IsolatedMediaDecoder.exif(bytes)
            requireNotNull(IsolatedMediaDecoder.decode(png, 32)).recycle()
            val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
            assertTrue(worker.isolated && !worker.internetGranted && !worker.runtimeInitialized)
            assertEquals(Process.myPid(), caller)
        }
    }

    @Test
    fun cameraSized108MegapixelSourceUsesABoundedOffCentreRegion() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = stripedPng(12000, 9000)
        assertEquals(ImageInspection(12000, 9000, "image/png", 1), IsolatedMediaDecoder.inspect(source))
        val region = requireNotNull(IsolatedMediaDecoder.region(source, MediaImageRegion(7000, 2000, 8000, 3000), 1000))
        try {
            assertEquals(1000, region.width)
            assertEquals(1000, region.height)
            assertEquals(Color.BLUE, region.getPixel(500, 500))
            assertTrue(requireNotNull(IsolatedMediaDecoder.lastIsolation).isolated)
        } finally {
            region.recycle()
        }
        assertNull(IsolatedMediaDecoder.region(source, MediaImageRegion(-1, 0, 1, 1), 1000))
        requireNotNull(IsolatedMediaDecoder.decode(image(Bitmap.CompressFormat.PNG), 2)).recycle()
    }

    @Test
    fun realHeifFixtureIsQualifiedOrRefusedWithoutAHostCodecFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        IsolatedMediaDecoder.install(instrumentation.targetContext)
        val source =
            android.util.Base64.decode(
                instrumentation.context.assets
                    .open("aosp-heifwriter-input.heic.b64")
                    .bufferedReader()
                    .use { it.readText() },
                android.util.Base64.DEFAULT,
            )
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(source)
                .joinToString("") { "%02x".format(it) }
        assertEquals("62dfb44160403ca8355a874cecc91cdbce57e98dd597fa36a2af55ef54c017ac", digest)
        val inspected = IsolatedMediaDecoder.inspect(source)
        val preview = IsolatedMediaDecoder.decode(source, 32)
        val region = IsolatedMediaDecoder.region(source, MediaImageRegion(0, 0, 32, 32), 32)
        try {
            if (inspected != null) {
                assertEquals(1920, inspected.width)
                assertEquals(1080, inspected.height)
            }
            for (bitmap in listOfNotNull(preview, region)) {
                assertTrue(bitmap.width in 1..32 && bitmap.height in 1..32)
                val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
                assertTrue(worker.isolated && worker.uid != Process.myUid() && !worker.internetGranted)
            }
            println(
                "MEDIA_FORMAT_QUALIFICATION format=HEIF api=${android.os.Build.VERSION.SDK_INT} " +
                    "inspect=${inspected != null} preview=${preview != null} region=${region != null}",
            )
        } finally {
            preview?.recycle()
            region?.recycle()
        }
        requireNotNull(IsolatedMediaDecoder.decode(image(Bitmap.CompressFormat.PNG), 2)).recycle()
    }

    @Test
    fun platformAnimationCorpusStreamsChangingPixelsWithinAdmission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        IsolatedMediaDecoder.install(instrumentation.targetContext)
        val fixtures =
            listOf(
                "aosp-animated.gif.b64" to "eec5e745032b9775d67f040d9ab95ae3dc296100ce0c5d6bf95667bf2d27d2a6",
                "aosp-animated_webp.webp.b64" to "00a108b42a6877076e96c3cde6d75e03c6eb80639489e05ddcf509867669f666",
            )
        for ((asset, hash) in fixtures) {
            val source =
                android.util.Base64.decode(
                    instrumentation.context.assets
                        .open(asset)
                        .bufferedReader()
                        .use { it.readText() },
                    android.util.Base64.DEFAULT,
                )
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(source)
                    .joinToString("") { "%02x".format(it) }
            assertEquals(hash, digest)
            val admission =
                dev.ipf.whitenoise.android.media
                    .admitAnimationSource(source)
            assertTrue(
                "CTS animation must remain usable: $admission",
                admission is dev.ipf.whitenoise.android.media.AnimationSourceAdmission.Admitted,
            )
            val animation =
                requireNotNull(
                    dev.ipf.whitenoise.android.media.MediaPipeline
                        .decodeAnimatedDrawable(source, 96),
                )
            val pixels =
                Bitmap.createBitmap(
                    animation.intrinsicWidth,
                    animation.intrinsicHeight,
                    Bitmap.Config.ARGB_8888,
                )
            try {
                val hashes = HashSet<Int>()
                val buffer = IntArray(pixels.width * pixels.height)
                instrumentation.runOnMainSync { (animation as android.graphics.drawable.Animatable).start() }
                val until = android.os.SystemClock.elapsedRealtime() + 3500L
                while (hashes.size < 2 && android.os.SystemClock.elapsedRealtime() < until) {
                    instrumentation.runOnMainSync {
                        animation.setBounds(0, 0, pixels.width, pixels.height)
                        animation.draw(android.graphics.Canvas(pixels))
                        pixels.getPixels(buffer, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                        hashes.add(buffer.contentHashCode())
                    }
                    Thread.sleep(25L)
                }
                assertTrue("Decoded CTS animation must show changing frames", hashes.size >= 2)
            } finally {
                instrumentation.runOnMainSync { (animation as java.io.Closeable).close() }
                pixels.recycle()
            }
        }
    }

    private fun image(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Generate a real encoded 108MP image one row at a time, without a 432MB fixture bitmap. */
    private fun stripedPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val row = ByteArray(1 + width * 3)
        for (x in 0 until width) row[1 + x * 3 + if (x < width / 2) 0 else 2] = 0xff.toByte()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream -> repeat(height) { stream.write(row) } }
        return ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
                chunk(
                    "IHDR",
                    ByteBuffer
                        .allocate(13)
                        .putInt(width)
                        .putInt(height)
                        .put(8)
                        .put(2)
                        .put(0)
                        .put(0)
                        .put(0)
                        .array(),
                )
                chunk("IDAT", compressed.toByteArray())
                chunk("IEND", byteArrayOf())
            }.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(
        type: String,
        data: ByteArray,
    ) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        write(ByteBuffer.allocate(4).putInt(data.size).array())
        write(tag)
        write(data)
        val crc =
            CRC32().apply {
                update(tag)
                update(data)
            }
        write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
