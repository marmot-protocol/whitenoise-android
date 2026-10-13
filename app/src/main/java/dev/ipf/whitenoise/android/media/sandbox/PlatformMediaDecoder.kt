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
}
