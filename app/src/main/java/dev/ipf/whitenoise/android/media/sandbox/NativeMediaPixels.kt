@file:Suppress("MagicNumber") // Packed RGBA byte offsets and orthogonal rotation arithmetic.

package dev.ipf.whitenoise.android.media.sandbox

import java.nio.ByteBuffer

/** Rotate raw RGBA pixels only; no compressed format is parsed in the parent process. */
internal object NativeMediaPixels {
    fun rotate(
        source: ByteBuffer,
        target: ByteBuffer,
        width: Int,
        height: Int,
        degrees: Int,
    ) {
        require(degrees == 90 || degrees == 180 || degrees == 270)
        val count =
            checkNotNull(
                MediaDecodePolicy.pixelBytes(width, height, MediaDecodePolicy.MAX_EDGE, source.capacity()),
            )
        require(target.capacity() >= count && source !== target)
        val input = source.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
        val output = target.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
        val outputWidth = if (degrees == 180) width else height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val destination =
                    when (degrees) {
                        90 -> x * outputWidth + (height - 1 - y)
                        180 -> (height - 1 - y) * outputWidth + (width - 1 - x)
                        else -> (width - 1 - x) * outputWidth + y
                    }
                // Both views keep their default byte order; the opaque four RGBA bytes are preserved.
                output.putInt(destination * 4, input.getInt((y * width + x) * 4))
            }
        }
    }
}
