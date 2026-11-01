package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max

/** Pixels together with the decode ceiling that produced them, including naturally small source images. */
internal data class MediaThumbnail(
    val bitmap: Bitmap,
    val maxEdgePx: Int,
) {
    /** A small original can satisfy its requested ceiling without being upscaled to that ceiling. */
    fun satisfies(requestedMaxEdgePx: Int): Boolean = maxEdgePx >= requestedMaxEdgePx
}

private const val PIXEL_ROUNDING_TOLERANCE = 0.001

private val thumbnailDecodeBuckets = intArrayOf(128, 256, 384, 512, 768, 1024, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)

/**
 * Selects a bounded decode for physical tile pixels, accounting for the source area cropped out by ContentScale.Crop.
 * Unknown source geometry keeps the existing ceiling; zero-sized layouts do not start a decode.
 */
internal fun thumbnailDecodeMaxEdge(
    widthPx: Int,
    heightPx: Int,
    sourceAspectRatio: Float?,
): Int {
    if (widthPx <= 0 || heightPx <= 0) return 0
    val ratio = sourceAspectRatio?.takeIf { it.isFinite() && it > 0f }
    val required =
        when {
            ratio == null -> MediaPipeline.THUMBNAIL_MAX_EDGE_PX.toDouble()
            ratio >= 1f -> max(widthPx.toDouble(), heightPx.toDouble() * ratio)
            else -> max(heightPx.toDouble(), widthPx.toDouble() / ratio)
        }
    val edge =
        ceil(required - PIXEL_ROUNDING_TOLERANCE)
            .coerceAtMost(MediaPipeline.THUMBNAIL_MAX_EDGE_PX.toDouble())
            .toInt()
    return thumbnailDecodeBuckets.first { it >= edge }
}
