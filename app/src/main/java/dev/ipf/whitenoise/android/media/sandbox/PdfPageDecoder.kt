package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory

internal object PdfPagePolicy {
    const val MAX_INPUT = 64L * 1024 * 1024
    const val MAX_PAGES = 1000
    const val MAX_EDGE = 2048
    const val MAX_BYTES = 16 * 1024 * 1024
    const val CACHE_BYTES = 32 * 1024 * 1024

    private const val MAX_PAGE_DIMENSION = 32768
    private const val BYTES_PER_PIXEL = 4

    @Suppress("ComplexCondition", "ReturnCount")
    fun size(
        width: Double,
        height: Double,
        edge: Int,
    ): Pair<Int, Int>? {
        if (!width.isFinite() ||
            !height.isFinite() ||
            width <= 0 ||
            height <= 0 ||
            width > MAX_PAGE_DIMENSION ||
            height > MAX_PAGE_DIMENSION ||
            edge !in 1..MAX_EDGE
        ) {
            return null
        }
        val scale = edge / maxOf(width, height)
        if (!scale.isFinite() || scale <= 0) return null
        val w = (width * scale).toInt().coerceIn(1, edge)
        val h = (height * scale).toInt().coerceIn(1, edge)
        return (w to h).takeIf { bytes(w, h) != null }
    }

    fun bytes(
        width: Int,
        height: Int,
    ): Int? =
        if (width in 1..MAX_EDGE && height in 1..MAX_EDGE) {
            (width.toLong() * height * BYTES_PER_PIXEL).takeIf { it <= MAX_BYTES }?.toInt()
        } else {
            null
        }
}

/** Only the isolated service constructs PdfRenderer. Each request owns its duplicate and one page. */
internal object PdfPageDecoder {
    @Suppress("NestedBlockDepth", "ReturnCount", "TooGenericExceptionCaught")
    fun execute(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        data: Bundle,
        response: Bundle,
    ): Bundle {
        response.putInt("pdfStatus", 1)
        if (input.statSize !in 1..PdfPagePolicy.MAX_INPUT) return response
        val owned = ParcelFileDescriptor.dup(input.fileDescriptor)
        val renderer =
            try {
                PdfRenderer(owned)
            } catch (failure: Throwable) {
                owned.close()
                if (failure is Exception) return response
                throw failure
            }
        renderer.use {
            val count = it.pageCount
            if (count !in 1..PdfPagePolicy.MAX_PAGES) return response
            response.putInt("pdfPages", count)
            val index = data.getInt("pdfPage")
            if (index !in 0 until count) return response
            it.openPage(index).use { page ->
                val size =
                    PdfPagePolicy.size(
                        page.width.toDouble(),
                        page.height.toDouble(),
                        data.getInt("edge"),
                    ) ?: return response
                response.putInt("pdfWidth", page.width)
                response.putInt("pdfHeight", page.height)
                if (data.getInt("operation") == MediaDecodePolicy.PDF_RENDER_PAGE) {
                    val bytes = PdfPagePolicy.bytes(size.first, size.second) ?: return response
                    if (bytes > output.size) return response
                    val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val buffer = output.mapReadWrite()
                        try {
                            buffer.limit(bytes)
                            bitmap.copyPixelsToBuffer(buffer)
                        } finally {
                            SharedMemory.unmap(buffer)
                        }
                        response.putInt("width", size.first)
                        response.putInt("height", size.second)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        response.putInt("pdfStatus", 0)
        response.putBoolean("success", true)
        return response
    }
}
