package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.GroupSvgRasterizer
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.admitAnimationSource
import kotlinx.coroutines.runBlocking

/** Codec implementation called by the isolated service; JVM tests supply an explicit test transport. */
internal object PlatformMediaDecoder {
    @Suppress("ReturnCount") // Resource/container guards reject bytes before platform parsing.
    fun inspect(bytes: ByteArray): ImageInspection? {
        if (bytes.size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES) return null
        if (admitAnimationSource(bytes) is AnimationSourceAdmission.Refused) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return ImageInspection(
            bounds.outWidth,
            bounds.outHeight,
            bounds.outMimeType?.take(MediaDecodePolicy.MAX_MIME_CHARS).orEmpty(),
            MediaPipeline.readExifOrientationInDecoder(bytes),
        )
    }

    @Suppress("ReturnCount") // Source and output budgets precede raster allocation.
    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
    ): Bitmap? {
        if (MediaDecodePolicy.outputCapacity(edge) == null) return null
        val info = inspect(bytes) ?: return null
        if (!MediaDecodePolicy.sourceAllowed(info.width, info.height)) return null
        return MediaPipeline.decodeSampledBitmapInDecoder(bytes, edge, oriented)
    }

    fun svg(bytes: ByteArray): Bitmap? {
        val raster = runBlocking { GroupSvgRasterizer.rasterizeInDecoder(bytes) }
        return decode(raster, MediaDecodePolicy.SVG_EDGE, false)
    }

    @Suppress("DEPRECATION", "ReturnCount") // API 30 region API; every native allocation is admitted first.
    fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? {
        if (MediaDecodePolicy.outputCapacity(edge) == null) return null
        val info = inspect(bytes) ?: return null
        if (!MediaDecodePolicy.sourceAllowed(info.width, info.height) || !region.validFor(info.width, info.height)) {
            return null
        }
        // Choose sampling without reducing the crop below its requested resolution. A codec's
        // power-of-two intermediate is independently bounded, then scaled down exactly.
        var sample = 1
        while (maxOf(region.width, region.height) / (sample * 2) >= edge) sample *= 2
        val width = (region.width.toLong() + sample - 1) / sample
        val height = (region.height.toLong() + sample - 1) / sample
        if (width * height > MediaDecodePolicy.MAX_REGION_DECODE_PIXELS) return null
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
        try {
            val options =
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val bounds = android.graphics.Rect(region.left, region.top, region.right, region.bottom)
            val decoded =
                decoder.decodeRegion(bounds, options)
                    ?: return null
            val target = MediaPipeline.targetDimensions(decoded.width, decoded.height, edge)
            return try {
                if (decoded.width == target.first && decoded.height == target.second) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, target.first, target.second, true).also { decoded.recycle() }
                }
            } catch (failure: Exception) {
                decoded.recycle()
                throw failure
            } catch (failure: OutOfMemoryError) {
                decoded.recycle()
                throw failure
            }
        } finally {
            decoder.recycle()
        }
    }
}
