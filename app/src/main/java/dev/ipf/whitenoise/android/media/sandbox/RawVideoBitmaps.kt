package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.os.SharedMemory
import java.io.IOException

/** Supplies parent-owned pixels for one raw frame. */
internal fun interface RawVideoBitmaps {
    fun obtain(
        width: Int,
        height: Int,
    ): Bitmap

    companion object {
        val Fresh = RawVideoBitmaps { width, height -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    }
}

/**
 * Copies one validated packed RGBA frame from worker output memory into parent-owned pixels.
 * [current] is checked after the copy so a retired session never publishes a frame.
 */
internal fun copyRawVideoFrame(
    memory: SharedMemory,
    output: AvStreamOutput,
    bitmaps: RawVideoBitmaps,
    current: () -> Boolean,
): AvStreamFrame.Video {
    val source = memory.mapReadOnly()
    try {
        source.limit(output.byteCount)
        val bitmap = bitmaps.obtain(output.width, output.height)
        var retained = false
        try {
            check(bitmap.byteCount == output.byteCount)
            bitmap.copyPixelsFromBuffer(source)
            if (!current()) throw IOException("Media session retired during output copy")
            return AvStreamFrame.Video(bitmap, output.timestampUs).also { retained = true }
        } finally {
            if (!retained) bitmap.recycle()
        }
    } finally {
        SharedMemory.unmap(source)
    }
}
