package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32

/**
 * Adversarial inputs for the four hand-written image container walkers behind
 * [MediaPipeline.stripOriginalImageMetadata] and
 * [MediaPipeline.sanitizeAnimatedImageMetadata].
 *
 * `MediaPipelineTest` covers these with well-formed containers it builds
 * itself. Nothing covered what they do with a container that is truncated,
 * declares a length past the buffer, declares a length of zero, or is simply
 * hostile bytes behind a valid magic number. Those are the shapes that reach
 * this code in practice: the bytes come from a content-provider stream, so a
 * share intent from any installed app can supply them under any declared type,
 * and the strip runs before anything else accepts them.
 *
 * The contract these pin is the one the functions already implement — fail
 * closed by returning null, never by throwing — plus termination, since each
 * walker advances its cursor by a length field taken from the input.
 *
 * Every timeout here is a termination assertion, not a performance budget.
 * Inputs are a few hundred bytes, so a walker that returns at all returns
 * immediately; a bound this loose only fires on a cursor that stops advancing.
 */
class MediaPipelineMalformedContainerTest {
    // ---- truncation ---------------------------------------------------------

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun everyTruncationOfEachContainerFailsClosed() {
        for ((label, container) in wellFormedContainers()) {
            for (length in 0..container.size) {
                val truncated = container.copyOf(length)
                val outcome = runCatching { MediaPipeline.stripOriginalImageMetadata(truncated) }
                assertTrue(
                    "$label truncated to $length bytes threw ${outcome.exceptionOrNull()}",
                    outcome.isSuccess,
                )
            }
        }
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun everyTruncationIsAlsoSafeOnTheAnimatedPath() {
        for ((label, container) in wellFormedContainers()) {
            for (length in 0..container.size) {
                val truncated = container.copyOf(length)
                val outcome = runCatching { MediaPipeline.sanitizeAnimatedImageMetadata(truncated) }
                assertTrue(
                    "$label truncated to $length bytes threw ${outcome.exceptionOrNull()}",
                    outcome.isSuccess,
                )
            }
        }
    }

    // ---- hostile length fields ---------------------------------------------

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun pngChunkLengthPastTheBufferIsRejected() {
        // A maximal 32-bit chunk length. The walker reads it as an unsigned
        // Long, so it must compare past the end rather than wrap to a negative
        // Int and move the cursor backwards.
        val png = pngWithFirstChunkLength(0xFFFFFFFFu.toLong())

        assertNull(MediaPipeline.stripOriginalImageMetadata(png))
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun pngChunkLengthOfZeroStillAdvancesPastTheHeader() {
        // A zero-length chunk is legal (IEND is one). The cursor must still
        // advance by the 12-byte chunk overhead or the walk never ends.
        val png = pngWithFirstChunkLength(0L)

        assertTrue(runCatching { MediaPipeline.stripOriginalImageMetadata(png) }.isSuccess)
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun webpChunkSizePastTheBufferIsRejected() {
        val webp = webpWithFirstChunkSize(0xFFFFFFFFu.toLong())

        assertNull(MediaPipeline.stripOriginalImageMetadata(webp))
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun webpChunkSizeOfZeroStillAdvancesPastTheHeader() {
        val webp = webpWithFirstChunkSize(0L)

        assertTrue(runCatching { MediaPipeline.stripOriginalImageMetadata(webp) }.isSuccess)
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun jpegSegmentLengthShorterThanItsOwnHeaderIsRejected() {
        // A declared segment length below 2 would leave the cursor inside the
        // header it just consumed.
        val jpeg =
            byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0x00, 0x00) +
                byteArrayOf(0xff.toByte(), 0xd9.toByte())

        assertTrue(runCatching { MediaPipeline.stripOriginalImageMetadata(jpeg) }.isSuccess)
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun jpegWithNoEndOfImageMarkerFailsClosed() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(64) { 0xff.toByte() }

        assertTrue(runCatching { MediaPipeline.stripOriginalImageMetadata(jpeg) }.isSuccess)
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun gifBlockSizeOfZeroTerminatesTheSubBlockChain() {
        // A zero-size sub-block is the chain terminator. A walker that treats
        // it as "advance by zero" instead of "stop" would spin here.
        val gif = "GIF89a".encodeToByteArray() + ByteArray(7) + byteArrayOf(0x21, 0xfe.toByte(), 0x00)

        assertTrue(runCatching { MediaPipeline.stripOriginalImageMetadata(gif) }.isSuccess)
    }

    // ---- hostile bytes behind a valid magic --------------------------------

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun randomBodiesBehindEachMagicNumberFailClosed() {
        // Deterministic: a fixed seed keeps a failure reproducible from the
        // message alone, which a random seed would not.
        val random = Random(SWEEP_SEED)
        val magics =
            listOf(
                "jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte()),
                "png" to PNG_SIGNATURE,
                "gif87a" to "GIF87a".encodeToByteArray(),
                "gif89a" to "GIF89a".encodeToByteArray(),
            )
        for ((label, magic) in magics) {
            repeat(SWEEP_CASES) { case ->
                val body = ByteArray(random.nextInt(MAX_SWEEP_BODY_BYTES)).also(random::nextBytes)
                val candidate = magic + body
                val outcome = runCatching { MediaPipeline.stripOriginalImageMetadata(candidate) }
                assertTrue(
                    "$label case $case (${candidate.size} bytes) threw ${outcome.exceptionOrNull()}",
                    outcome.isSuccess,
                )
            }
        }
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun randomWebpBodiesFailClosed() {
        // WebP needs its size field and the WEBP fourcc to get past the sniff,
        // so it is built rather than prefixed like the others.
        val random = Random(SWEEP_SEED)
        repeat(SWEEP_CASES) { case ->
            val body = ByteArray(random.nextInt(MAX_SWEEP_BODY_BYTES)).also(random::nextBytes)
            val candidate =
                "RIFF".encodeToByteArray() +
                    u32le(body.size + 4) +
                    "WEBP".encodeToByteArray() +
                    body
            val outcome = runCatching { MediaPipeline.stripOriginalImageMetadata(candidate) }
            assertTrue(
                "webp case $case (${candidate.size} bytes) threw ${outcome.exceptionOrNull()}",
                outcome.isSuccess,
            )
        }
    }

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun bytesWithNoRecognizedMagicReturnNull() {
        assertNull(MediaPipeline.stripOriginalImageMetadata(ByteArray(0)))
        assertNull(MediaPipeline.stripOriginalImageMetadata(byteArrayOf(0x00)))
        assertNull(MediaPipeline.stripOriginalImageMetadata("not an image at all".encodeToByteArray()))
    }

    // ---- the well-formed baseline still works ------------------------------

    @Test(timeout = TERMINATION_TIMEOUT_MS)
    fun theWellFormedFixturesUsedAboveAreAcceptedUnchangedInKind() {
        // Guards the negative assertions: if the fixtures stopped being valid
        // containers, every "fails closed" case above would pass vacuously.
        for ((label, container) in wellFormedContainers()) {
            assertNotNull(
                "$label fixture is no longer a recognized container",
                MediaPipeline.sniffImageMediaType(container),
            )
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private fun wellFormedContainers(): List<Pair<String, ByteArray>> =
        listOf(
            "jpeg" to minimalJpeg(),
            "png" to minimalPng(),
            "webp" to minimalWebp(),
            "gif" to minimalGif(),
        )

    private fun minimalJpeg(): ByteArray =
        byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
            jpegSegment(0xe1, "Exif  ".encodeToByteArray() + ByteArray(8)) +
            jpegSegment(0xda, byteArrayOf(0, 1, 2, 3)) +
            byteArrayOf(0x11, 0x22, 0xff.toByte(), 0xd9.toByte())

    private fun jpegSegment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    private fun minimalPng(): ByteArray =
        PNG_SIGNATURE +
            pngChunk("IHDR", ByteArray(13)) +
            pngChunk("tEXt", "private note".encodeToByteArray()) +
            pngChunk("IEND", ByteArray(0))

    private fun pngWithFirstChunkLength(length: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        out.write(u32be(length))
        out.write("IHDR".encodeToByteArray())
        out.write(ByteArray(13))
        out.write(u32be(0L))
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun pngChunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32be(payload.size.toLong()))
        out.write(type.encodeToByteArray())
        out.write(payload)
        val crc = CRC32()
        crc.update(type.encodeToByteArray())
        crc.update(payload)
        out.write(u32be(crc.value))
        return out.toByteArray()
    }

    private fun minimalWebp(): ByteArray {
        val body = webpChunk("VP8L", byteArrayOf(0x2f, 0x00, 0x00, 0x00, 0x00))
        return "RIFF".encodeToByteArray() + u32le(body.size + 4) + "WEBP".encodeToByteArray() + body
    }

    private fun webpWithFirstChunkSize(size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".encodeToByteArray())
        out.write(u32le(16))
        out.write("WEBP".encodeToByteArray())
        out.write("VP8L".encodeToByteArray())
        out.write(u32le(size))
        out.write(ByteArray(4))
        return out.toByteArray()
    }

    private fun webpChunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.encodeToByteArray())
        out.write(u32le(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun minimalGif(): ByteArray {
        val headerAndScreen =
            "GIF89a".encodeToByteArray() + byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00)
        val globalColors = byteArrayOf(0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val commentBytes = "private comment".encodeToByteArray()
        val comment = byteArrayOf(0x21, 0xfe.toByte(), commentBytes.size.toByte()) + commentBytes + byteArrayOf(0)
        val frame =
            byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0x00)
        return headerAndScreen + globalColors + comment + frame + byteArrayOf(0x3b)
    }

    private fun u32be(value: Long): ByteArray =
        byteArrayOf(
            ((value ushr 24) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            (value and 0xff).toByte(),
        )

    private fun u32le(value: Int): ByteArray = u32le(value.toLong())

    private fun u32le(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )

    private companion object {
        const val TERMINATION_TIMEOUT_MS = 30_000L
        const val SWEEP_SEED = 0x5748_4954_454eL // "WHITEN"
        const val SWEEP_CASES = 400
        const val MAX_SWEEP_BODY_BYTES = 512
        val PNG_SIGNATURE =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            )
    }
}
