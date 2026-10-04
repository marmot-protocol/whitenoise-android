package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random

/** Generated, decodable media for genuine sends. Nothing here reads a user file or personal account. */
internal object FixtureMediaAssets {
    private const val BLOCK_PIXELS = 32
    private const val JPEG_QUALITY = 85
    private const val FREE_BOX_HEADER_BYTES = 8
    private const val CHANNEL_RANGE = 256
    private const val VIDEO_ASSET = "silent-black-10s.mp4.b64"

    /** A real JPEG whose noisy blocks keep it large enough to be a meaningful image rather than a trivial constant. */
    fun jpeg(
        seed: Long,
        width: Int = 1280,
        height: Int = 960,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(seed)
        val paint = Paint()
        for (y in 0 until height step BLOCK_PIXELS) {
            for (x in 0 until width step BLOCK_PIXELS) {
                val red = random.nextInt(CHANNEL_RANGE)
                val green = random.nextInt(CHANNEL_RANGE)
                paint.color = Color.rgb(red, green, random.nextInt(CHANNEL_RANGE))
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + BLOCK_PIXELS).toFloat(),
                    (y + BLOCK_PIXELS).toFloat(),
                    paint,
                )
            }
        }
        return ByteArrayOutputStream().use { output ->
            val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            check(encoded) { "generated JPEG was not encoded" }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    /** The bundled benign 10 second H.264/AAC clip, decoded from the instrumentation package's own assets. */
    fun video(): ByteArray {
        val encoded =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open(VIDEO_ASSET)
                .use { it.readBytes() }
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    /**
     * Grows a valid MP4 to [targetBytes] with a top-level `free` box, which every ISO BMFF reader skips, so the result
     * still decodes and plays. It tests size thresholds only and is not representative throughput evidence.
     */
    fun paddedVideo(
        base: ByteArray,
        targetBytes: Int,
        seed: Long,
    ): ByteArray {
        require(targetBytes > base.size + FREE_BOX_HEADER_BYTES)
        val padding = targetBytes - base.size
        val box = ByteBuffer.allocate(padding)
        box.putInt(padding)
        box.put("free".toByteArray(Charsets.US_ASCII))
        val filler = ByteArray(padding - FREE_BOX_HEADER_BYTES)
        Random(seed).nextBytes(filler)
        box.put(filler)
        return base + box.array()
    }

    /**
     * Grows a valid JPEG to [targetBytes] with random bytes after its end-of-image marker, which decoders ignore, so
     * the result still decodes. It exists to make an image large enough to observe a transfer, not to measure
     * throughput.
     */
    fun paddedJpeg(
        base: ByteArray,
        targetBytes: Int,
        seed: Long,
    ): ByteArray {
        require(targetBytes > base.size)
        val filler = ByteArray(targetBytes - base.size)
        Random(seed).nextBytes(filler)
        return base + filler
    }

    /** One generated attachment as the shipping send path accepts it, with the digest the readback must reproduce. */
    fun attachment(
        bytes: ByteArray,
        mediaType: String,
        fileName: String,
        dim: String? = null,
    ) = PendingAttachment(bytes, mediaType, fileName, dim)
}
