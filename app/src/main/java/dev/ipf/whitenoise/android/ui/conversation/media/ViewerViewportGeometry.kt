package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion
import dev.ipf.whitenoise.android.ui.common.viewerFitGeometry
import kotlin.math.ceil
import kotlin.math.floor

internal const val IMAGE_VIEWER_MAX_SCALE = 1024f
internal const val VIEWER_DETAIL_EDGE = 2048
private const val VIEWER_FALLBACK_EDGE = 4096.0
private const val VIEWER_FALLBACK_PIXELS = 4_194_304.0
private const val VIEWER_BASE_EDGE = 2560

/**
 * Geometry uses original source dimensions (the chat preview uses raw EXIF coordinates), even while its sampled
 * preview is displayed.
 */
internal data class ViewerViewportGeometry(
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val scale: Float,
    val offset: Offset,
) {
    private val valid get() =
        width > 0 &&
            height > 0 &&
            dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy
                .sourceAllowed(sourceWidth, sourceHeight) &&
            scale.isFinite() &&
            scale in 1f..IMAGE_VIEWER_MAX_SCALE &&
            offset.x.isFinite() &&
            offset.y.isFinite()
    private val detailEligible get() = valid && scale > 1f
    private val fit get() = viewerFitGeometry(width.toFloat(), height.toFloat(), sourceWidth, sourceHeight)
    val sourcePixelScale: Float get() = if (sourceWidth > 0) fit.baseWidth * scale / sourceWidth else 0f

    fun sourceRectOnScreen(region: MediaImageRegion): Rect =
        sourceRectOnScreen(
            Rect(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            ),
        )

    fun sourceRectOnScreen(region: Rect): Rect {
        if (!valid) return Rect.Zero
        val pixelScale = sourcePixelScale
        val left = (width - fit.baseWidth * scale) / 2f + offset.x
        val top = (height - fit.baseHeight * scale) / 2f + offset.y
        return Rect(
            left + region.left * pixelScale,
            top + region.top * pixelScale,
            left + region.right * pixelScale,
            top + region.bottom * pixelScale,
        )
    }

    /** SVG coordinates remain fractional even for a24-unit document at1024× zoom. */
    fun vectorDetailRegion(): ViewerSvgRegion? {
        val step = sourcePixelScale.toDouble()
        if (!detailEligible || !step.isFinite() || step <= 0.0) return null
        val full = sourceRectOnScreen(MediaImageRegion(0, 0, sourceWidth, sourceHeight))
        val quantum = (maxOf(width, height) / step / 32.0).coerceAtLeast(0.000000001)

        fun lower(
            value: Double,
            limit: Int,
        ) = (floor(value / quantum) * quantum - quantum * 2).coerceIn(0.0, limit.toDouble())

        fun upper(
            value: Double,
            limit: Int,
        ) = (ceil(value / quantum) * quantum + quantum * 2).coerceIn(0.0, limit.toDouble())
        return ViewerSvgRegion(
            lower(-full.left / step, sourceWidth),
            lower(-full.top / step, sourceHeight),
            upper((width - full.left) / step, sourceWidth),
            upper((height - full.top) / step, sourceHeight),
        ).takeIf { it.validFor(sourceWidth, sourceHeight) }
    }

    /** Quantized outward to deduplicate tiny movements, with a modest halo for settling pans. */
    fun detailRegion(): MediaImageRegion? {
        val step = sourcePixelScale
        if (!detailEligible || !step.isFinite() || step <= 0f) return null
        val full = sourceRectOnScreen(MediaImageRegion(0, 0, sourceWidth, sourceHeight))
        val quantum = maxOf(1, ceil(maxOf(width, height) / step / 16f).toInt())
        val halo = quantum * 2

        fun lower(
            value: Float,
            limit: Int,
        ) = ((floor(value / quantum).toInt() * quantum) - halo).coerceIn(0, limit)

        fun upper(
            value: Float,
            limit: Int,
        ) = ((ceil(value / quantum).toInt() * quantum) + halo).coerceIn(0, limit)
        val region =
            MediaImageRegion(
                lower(-full.left / step, sourceWidth),
                lower(-full.top / step, sourceHeight),
                upper((width - full.left) / step, sourceWidth),
                upper((height - full.top) / step, sourceHeight),
            )
        return region.takeIf { it.validFor(sourceWidth, sourceHeight) }
    }
}

/** A whole-image fallback may improve a long/narrow source even when its platform region decoder refuses it. */
internal fun viewerFallbackEdge(
    width: Int,
    height: Int,
): Int? {
    if (width <= 0 || height <= 0) return null
    val pixels = width.toDouble() * height
    val maximum = maxOf(width, height)
    val factor = minOf(1.0, VIEWER_FALLBACK_EDGE / maximum, kotlin.math.sqrt(VIEWER_FALLBACK_PIXELS / pixels))
    val edge = floor(maximum * factor).toInt()
    return edge.takeIf { it > minOf(maximum, VIEWER_BASE_EDGE) }
}

/** Raster codecs need integer pixels; vector renderers need the actual fractional document viewport. */
internal sealed interface ViewerDetailRegion {
    val rectangle: Rect

    data class Raster(
        val region: MediaImageRegion,
    ) : ViewerDetailRegion {
        override val rectangle get() =
            Rect(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            )
    }

    data class Vector(
        val region: ViewerSvgRegion,
    ) : ViewerDetailRegion {
        override val rectangle get() =
            Rect(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            )
    }
}

internal fun ViewerViewportGeometry.detailRequest(vector: Boolean): ViewerDetailRegion? =
    if (vector) {
        vectorDetailRegion()?.let {
            ViewerDetailRegion.Vector(it)
        }
    } else {
        detailRegion()?.let {
            ViewerDetailRegion.Raster(it)
        }
    }
