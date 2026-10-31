package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/** Synthetic 2048x1024 canvases with changing pixels away from the origin; no downloaded media. */
internal object ViewerAnimationDetailFixtures {
    const val WIDTH = 2048
    const val HEIGHT = 1024
    const val X = 1536
    const val Y = 512

    fun gif(): ByteArray {
        val header = "GIF89a".toByteArray() + le(WIDTH, 2) + le(HEIGHT, 2) + byteArrayOf(0x80.toByte(), 0, 0)
        val palette = byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0xff.toByte())
        val loop = byteArrayOf(0x21, 0xff.toByte(), 0x0b) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
        return header + palette + loop + gifFrame(0) + gifFrame(1) + byteArrayOf(0x3b)
    }

    private fun gifFrame(index: Int): ByteArray {
        val control = byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0)
        val descriptor = byteArrayOf(0x2c) + le(X, 2) + le(Y, 2) + byteArrayOf(1, 0, 1, 0, 0)
        val packed = 4 or (index shl 3) or (5 shl 6)
        return control + descriptor + byteArrayOf(2, 2, (packed and 0xff).toByte(), (packed shr 8).toByte(), 0)
    }

    fun webp(): ByteArray {
        val canvas = byteArrayOf(0x12, 0, 0, 0) + le(WIDTH - 1, 3) + le(HEIGHT - 1, 3)
        val rectangle = le(X / 2, 3) + le(Y / 2, 3) + le(1, 3) + le(1, 3) + le(100, 3) + byteArrayOf(0)
        val body =
            "WEBP".toByteArray() + chunk("VP8X", canvas) + chunk("ANIM", ByteArray(6)) +
                chunk(
                    "ANMF",
                    rectangle + losslessFrame(Color.RED),
                ) + chunk("ANMF", rectangle + losslessFrame(Color.BLUE))
        return "RIFF".toByteArray() + le(body.size, 4) + body
    }

    private fun losslessFrame(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val encoded =
            try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        check(String(encoded, 0, 4, Charsets.US_ASCII) == "RIFF")
        var offset = 12
        while (offset < encoded.size) {
            check(offset <= encoded.size - 8)
            val size =
                (0..3).fold(0L) { value, i ->
                    value or ((encoded[offset + 4 + i].toLong() and 0xff) shl (8 * i))
                }
            val end = offset + 8L + size + (size and 1)
            check(end <= encoded.size)
            if (String(encoded, offset, 4, Charsets.US_ASCII) == "VP8L") return encoded.copyOfRange(offset, end.toInt())
            offset = end.toInt()
        }
        error("Platform encoder did not provide a lossless WebP frame")
    }

    private fun chunk(
        tag: String,
        data: ByteArray,
    ): ByteArray =
        tag.toByteArray() +
            le(data.size, 4) +
            data +
            if (data.size and 1 == 1) byteArrayOf(0) else byteArrayOf()

    private fun le(
        value: Int,
        size: Int,
    ): ByteArray = ByteArray(size) { (value ushr (8 * it)).toByte() }
}
