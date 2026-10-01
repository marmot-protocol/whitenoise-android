package dev.ipf.whitenoise.android.core

/**
 * A looping two-frame GIF whose logical screen is [width] x [height], each frame a single pixel in one of
 * two palette colours. Small enough to read by eye, real enough for the platform decoders.
 */
internal fun twoFrameGif(
    width: Int = 1,
    height: Int = 1,
): ByteArray {
    val screen =
        "GIF89a".encodeToByteArray() +
            byteArrayOf(
                (width and 0xff).toByte(),
                (width shr 8).toByte(),
                (height and 0xff).toByte(),
                (height shr 8).toByte(),
                0x80.toByte(),
                0,
                0,
            )
    val palette = byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0xff.toByte())
    val loop = byteArrayOf(0x21, 0xff.toByte(), 0x0b) + "NETSCAPE2.0".encodeToByteArray() + byteArrayOf(3, 1, 0, 0, 0)
    return screen + palette + loop + gifFrame(colorIndex = 0) + gifFrame(colorIndex = 1) + byteArrayOf(0x3b)
}

/** One 1x1 frame with a 100 ms delay, encoded as LZW clear / [colorIndex] / end. */
private fun gifFrame(colorIndex: Int): ByteArray {
    val control = byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0)
    val descriptor = byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0)
    // Minimum code size 2: clear (4), the colour index, end (5), packed LSB-first into 3-bit codes.
    val packed = 4 or (colorIndex shl 3) or (5 shl 6)
    val data = byteArrayOf(2, 2, (packed and 0xff).toByte(), (packed shr 8).toByte(), 0)
    return control + descriptor + data
}
