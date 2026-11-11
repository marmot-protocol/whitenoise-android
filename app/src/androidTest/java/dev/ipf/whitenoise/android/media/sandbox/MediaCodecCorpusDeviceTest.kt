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
        val marker = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x7f, 0xff.toByte())
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
            val decoded =
                if (index >= 6) {
                    contained("svg #$index") { IsolatedMediaDecoder.svg(bytes) }
                } else {
                    contained("decode #$index") { IsolatedMediaDecoder.decode(bytes, 32) }
                }
            // Native codecs may salvage truncated images. Containment, output bounds and recovery
            // are the guarantee, rather than claiming every decoder rejects every mutation.
            val limit = if (index >= 6) MediaDecodePolicy.SVG_EDGE else 32
            decoded?.let {
                assertTrue(it.width in 1..limit && it.height in 1..limit)
                it.recycle()
            }
            if (index == 2) contained("exif #$index") { IsolatedMediaDecoder.exif(bytes) }
            // Healthy recovery is checked on its own, after the malformed operation's evidence.
            requireNotNull(contained("healthy png after #$index") { IsolatedMediaDecoder.decode(png, 32) }).recycle()
            assertEquals(Process.myPid(), caller)
        }
    }

    @Test
    fun cameraSized108MegapixelSourceUsesABoundedOffCentreRegion() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = stripedPng(12000, 9000)
        assertEquals(
            ImageInspection(12000, 9000, "image/png", 1),
            contained("camera inspect") { IsolatedMediaDecoder.inspect(source) },
        )
        val region =
            requireNotNull(
                contained("camera region") {
                    IsolatedMediaDecoder.region(source, MediaImageRegion(7000, 2000, 8000, 3000), 1000)
                },
            )
        try {
            assertEquals(1000, region.width)
            assertEquals(1000, region.height)
            assertEquals(Color.BLUE, region.getPixel(500, 500))
        } finally {
            region.recycle()
        }
        assertNull(
            contained("invalid region") {
                IsolatedMediaDecoder.region(source, MediaImageRegion(-1, 0, 1, 1), 1000)
            },
        )
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
        // Each HEIF operation carries its own evidence rather than sharing the last receipt.
        val inspected = contained("heif inspect") { IsolatedMediaDecoder.inspect(source) }
        val preview = contained("heif preview") { IsolatedMediaDecoder.decode(source, 32) }
        val region =
            contained("heif region") { IsolatedMediaDecoder.region(source, MediaImageRegion(0, 0, 32, 32), 32) }
        try {
            if (inspected != null) {
                assertEquals(1920, inspected.width)
                assertEquals(1080, inspected.height)
            }
            for (bitmap in listOfNotNull(preview, region)) {
                assertTrue(bitmap.width in 1..32 && bitmap.height in 1..32)
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
                "aosp-webp_animated.webp.b64" to "292753f066add623af1e30bbeeca58f15b3d6d1052e12b13e30f21f8d3c14505",
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
            assertChangingFrames(animation as IsolatedAnimationDrawable)
        }
    }

    @Test
    fun rotatedWebpIsQualifiedOrSafelyRefusedWithoutDisablingOrdinaryAnimation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        IsolatedMediaDecoder.install(instrumentation.targetContext)
        val source =
            android.util.Base64.decode(
                instrumentation.context.assets
                    .open("aosp-animated_webp.webp.b64")
                    .bufferedReader()
                    .use { it.readText() },
                android.util.Base64.DEFAULT,
            )
        val animation =
            dev.ipf.whitenoise.android.media.MediaPipeline
                .decodeAnimatedDrawable(source, 96)
        println(
            "MEDIA_FORMAT_QUALIFICATION format=rotatedWebP " +
                "api=${android.os.Build.VERSION.SDK_INT} animation=${animation != null}",
        )
        if (animation != null) assertChangingFrames(animation as IsolatedAnimationDrawable)
        requireNotNull(IsolatedMediaDecoder.decode(image(Bitmap.CompressFormat.PNG), 2)).recycle()
    }

    private fun assertChangingFrames(animation: IsolatedAnimationDrawable) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pixels = Bitmap.createBitmap(animation.intrinsicWidth, animation.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val buffer = IntArray(pixels.width * pixels.height)

        fun sample(): Pair<Long, Int> {
            var result = 0L to 0
            instrumentation.runOnMainSync {
                pixels.eraseColor(Color.TRANSPARENT)
                animation.setBounds(0, 0, pixels.width, pixels.height)
                animation.draw(android.graphics.Canvas(pixels))
                pixels.getPixels(buffer, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                result = animation.presentedFrame to buffer.contentHashCode()
            }
            return result
        }
        try {
            val first = sample() // create() has already waited for the first isolated decoded frame.
            assertEquals(1L, first.first)
            instrumentation.runOnMainSync { animation.start() }
            val until = android.os.SystemClock.elapsedRealtime() + 3500L
            var later = first
            while ((later.first == first.first || later.second == first.second) &&
                android.os.SystemClock.elapsedRealtime() < until
            ) {
                later = sample()
                Thread.sleep(25L)
            }
            assertTrue(
                "A later accepted frame must have different pixels",
                later.first > first.first && later.second != first.second,
            )
        } finally {
            instrumentation.runOnMainSync { animation.close() }
            pixels.recycle()
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

    /**
     * Runs one decoder operation and checks the evidence that operation itself produced (#3722). Every operation
     * here must reach exactly one isolated worker, and any result must carry that worker's own receipt, so a
     * `null` from an in-process decode cannot pass as a refusal.
     */
    private fun <T> contained(
        operation: String,
        call: () -> T?,
    ): T? {
        val dispatchesBefore = IsolatedMediaDecoder.dispatchCount
        val receiptsBefore = IsolatedMediaDecoder.receiptCount
        val result = call()
        checkOperationEvidence(
            operation,
            dispatched = IsolatedMediaDecoder.dispatchCount - dispatchesBefore,
            receipts = IsolatedMediaDecoder.receiptCount - receiptsBefore,
            receipt = IsolatedMediaDecoder.lastIsolation,
            producedResult = result != null,
            hostUid = Process.myUid(),
        )
        return result
    }

    /** Missing, stale, duplicated, non-isolated and undispatched null evidence each fail the containment check. */
    @Test
    fun operationEvidenceRejectsMissingStaleAndNonIsolatedReceipts() {
        val isolated = WorkerIsolation(99_999, true, false, false, true, 13, -1)
        val host = Process.myUid()
        checkOperationEvidence("isolated", 1, 1, isolated, producedResult = true, hostUid = host)
        checkOperationEvidence("worker rejected", 1, 1, isolated, producedResult = false, hostUid = host)
        checkOperationEvidence("worker died", 1, 0, null, producedResult = false, hostUid = host)
        // A worker that threw sends an empty reply, so only the Binder uid attests where it ran.
        val threw = WorkerIsolation(99_875, false, false, false, false, 0, -1)
        checkOperationEvidence("worker threw", 1, 1, threw, producedResult = false, hostUid = host)
        val failures =
            listOf(
                // A null result with no dispatch may be a host decode that failed, not a refusal.
                EvidenceCase(0L, 0L, null, producedResult = false),
                // A result with no dispatch is a host decode.
                EvidenceCase(0L, 0L, isolated),
                // A dispatched result without its own receipt relies on a stale one.
                EvidenceCase(1L, 0L, isolated),
                // Two dispatches cannot be attributed to one operation.
                EvidenceCase(2L, 2L, isolated),
                // Even an empty failure reply must come from an isolated uid, never the app or another app.
                EvidenceCase(1L, 1L, isolated.copy(uid = host), producedResult = false),
                EvidenceCase(1L, 1L, isolated.copy(uid = 10_500), producedResult = false),
                // A receipt from a non-isolated, host-uid, networked or initialised process is not containment.
                EvidenceCase(1L, 1L, isolated.copy(isolated = false)),
                EvidenceCase(1L, 1L, isolated.copy(uid = host)),
                EvidenceCase(1L, 1L, isolated.copy(internetGranted = true)),
                EvidenceCase(1L, 1L, isolated.copy(runtimeInitialized = true)),
            )
        failures.forEach { case ->
            val error = runCatching { case.check(host) }.exceptionOrNull()
            assertTrue("$case passed", error is AssertionError)
        }
    }

    /** One forged evidence shape for the negative controls. */
    private data class EvidenceCase(
        val dispatched: Long,
        val receipts: Long,
        val receipt: WorkerIsolation?,
        val producedResult: Boolean = true,
    ) {
        /** Applies the containment rule to this forged shape. */
        fun check(hostUid: Int) {
            checkOperationEvidence(
                operation = "forged",
                dispatched = dispatched,
                receipts = receipts,
                receipt = receipt,
                producedResult = producedResult,
                hostUid = hostUid,
            )
        }
    }
}

/** The containment rule for one operation's evidence, throwing [AssertionError] when it does not hold. */
internal fun checkOperationEvidence(
    operation: String,
    dispatched: Long,
    receipts: Long,
    receipt: WorkerIsolation?,
    producedResult: Boolean,
    hostUid: Int,
) {
    assertEquals("$operation must reach exactly one worker", 1L, dispatched)
    if (receipts == 0L) {
        // The worker died or timed out, so containment holds only if nothing came back.
        assertTrue("$operation produced output without its own receipt", !producedResult)
        return
    }
    assertEquals("$operation must carry exactly one receipt", 1L, receipts)
    val worker = requireNotNull(receipt)
    // The uid comes from Binder, so the worker cannot forge it.
    assertTrue(
        "$operation was answered outside an isolated uid: $worker (host uid $hostUid)",
        worker.uid != hostUid && (worker.uid % PER_USER_UID_RANGE) in ISOLATED_APP_IDS,
    )
    // A worker that threw replies without its attestation, which is acceptable only when nothing came back.
    if (producedResult) {
        assertTrue(
            "$operation returned output from a worker that did not attest isolation: $worker",
            worker.isolated && !worker.internetGranted && !worker.runtimeInitialized,
        )
    }
}

/** Android's per-user uid stride and its isolated and app-zygote isolated app ids. */
private const val PER_USER_UID_RANGE = 100_000
private val ISOLATED_APP_IDS = 90_000..99_999
