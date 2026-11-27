package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

internal const val DISPLAY_AVATAR_MAX_EDGE = 256

internal data class DisplayAvatarPixels(
    val bitmap: Bitmap,
    val animated: Boolean?,
)

/** Serializes validated display pixels. Incoming image formats remain the isolated worker's responsibility. */
internal object DisplayAvatarCodec {
    private const val BYTES_PER_PIXEL = 4
    private const val HEADER_BYTES = 12
    private const val RAW = 1
    private const val DEFLATE = 2
    private const val STATIC = 0
    private const val ANIMATED = 1
    private const val UNKNOWN = 2
    const val MAX_ENCODED_BYTES = HEADER_BYTES + DISPLAY_AVATAR_MAX_EDGE * DISPLAY_AVATAR_MAX_EDGE * BYTES_PER_PIXEL

    fun encode(pixels: DisplayAvatarPixels): ByteArray? {
        val source = pixels.bitmap
        val supportedFormat = !source.isRecycled && source.config == Bitmap.Config.ARGB_8888
        if (!supportedFormat || (source.hasAlpha() && !source.isPremultiplied)) return null
        val bitmap = displaySized(source)
        return try {
            encodeDisplayPixels(bitmap, pixels.animated)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun displaySized(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        if (edge <= DISPLAY_AVATAR_MAX_EDGE && bitmap.colorSpace == srgb) return bitmap
        val scale = minOf(1f, DISPLAY_AVATAR_MAX_EDGE.toFloat() / edge)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, srgb).also { display ->
            Canvas(display).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun encodeDisplayPixels(
        bitmap: Bitmap,
        animated: Boolean?,
    ): ByteArray? {
        val width = bitmap.width
        val height = bitmap.height
        if (!validDimensions(width, height) || bitmap.rowBytes != width * BYTES_PER_PIXEL) {
            return null
        }
        val raw = ByteArray(width * height * BYTES_PER_PIXEL)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(raw))
        val compressed = deflate(raw)
        val payload = compressed ?: raw
        val animation =
            when (animated) {
                true -> ANIMATED
                false -> STATIC
                null -> UNKNOWN
            }
        return ByteBuffer
            .allocate(HEADER_BYTES + payload.size)
            .putShort(width.toShort())
            .putShort(height.toShort())
            .put((if (compressed == null) RAW else DEFLATE).toByte())
            .put(animation.toByte())
            .putShort(0)
            .putInt(raw.size)
            .put(payload)
            .array()
    }

    /** Only authenticated cache plaintext may be passed here. Malformed or unsupported entries are misses. */
    @Suppress("ReturnCount") // Validate every header field before allocating a bitmap.
    fun decode(encoded: ByteArray): DisplayAvatarPixels? {
        if (encoded.size !in (HEADER_BYTES + 1)..MAX_ENCODED_BYTES) return null
        val buffer = ByteBuffer.wrap(encoded)
        val width = buffer.short.toInt()
        val height = buffer.short.toInt()
        val format = buffer.get().toInt()
        val animated =
            when (buffer.get().toInt()) {
                STATIC -> false
                ANIMATED -> true
                UNKNOWN -> null
                else -> return null
            }
        if (buffer.short.toInt() != 0 || !validDimensions(width, height)) return null
        val expected = width * height * BYTES_PER_PIXEL
        if (buffer.int != expected) return null
        val raw = readPixelPayload(format, buffer, encoded, expected) ?: return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (bitmap.rowBytes != width * BYTES_PER_PIXEL) {
            bitmap.recycle()
            return null
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
        bitmap.prepareToDraw()
        return DisplayAvatarPixels(bitmap, animated)
    }

    private fun readPixelPayload(
        format: Int,
        buffer: ByteBuffer,
        encoded: ByteArray,
        expected: Int,
    ): ByteArray? =
        when (format) {
            RAW -> if (buffer.remaining() == expected) ByteArray(expected).also { buffer.get(it) } else null
            DEFLATE -> inflate(encoded, expected)
            else -> null
        }

    private fun validDimensions(
        width: Int,
        height: Int,
    ): Boolean = width in 1..DISPLAY_AVATAR_MAX_EDGE && height in 1..DISPLAY_AVATAR_MAX_EDGE

    private fun deflate(raw: ByteArray): ByteArray? {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val compressed = ByteArray(raw.size)
            val length = deflater.deflate(compressed)
            compressed.copyOf(length).takeIf { deflater.finished() && length in 1 until raw.size }
        } finally {
            deflater.end()
        }
    }

    private fun inflate(
        encoded: ByteArray,
        expected: Int,
    ): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(encoded, HEADER_BYTES, encoded.size - HEADER_BYTES)
            val raw = ByteArray(expected)
            val length = inflater.inflate(raw)
            val extra = if (inflater.finished()) 0 else inflater.inflate(ByteArray(1))
            raw.takeIf { length == expected && extra == 0 && inflater.finished() && inflater.remaining == 0 }
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
