package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgDecoder
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

    /** Maps an integer codec crop into its scaled, panned position in viewport pixels. */
    fun sourceRectOnScreen(region: MediaImageRegion): Rect =
        sourceRectOnScreen(
            Rect(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            ),
        )

    /** Maps fractional document bounds without fitting each detail crop independently. */
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

        /** Includes the quantization halo before clamping the leading source edge. */
        fun lower(
            value: Double,
            limit: Int,
        ) = (floor(value / quantum) * quantum - quantum * 2).coerceIn(0.0, limit.toDouble())

        /** Includes the quantization halo before clamping the trailing source edge. */
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

        /** Includes the quantization halo before clamping the leading source edge. */
        fun lower(
            value: Float,
            limit: Int,
        ) = ((floor(value / quantum).toInt() * quantum) - halo).coerceIn(0, limit)

        /** Includes the quantization halo before clamping the trailing source edge. */
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

    /** Raster crop and requested output edge form one resolution-aware decode key. */
    data class Raster(
        val region: MediaImageRegion,
        val edge: Int = VIEWER_DETAIL_EDGE,
    ) : ViewerDetailRegion {
        override val rectangle get() =
            Rect(
                region.left.toFloat(),
                region.top.toFloat(),
                region.right.toFloat(),
                region.bottom.toFloat(),
            )
    }

    /** Pixel demand belongs to the request key so a resized viewport cannot reuse an undersampled tile. */
    data class Vector(
        val region: ViewerSvgRegion,
        val edge: Int,
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

/** Screen-pixel demand includes the halo; raster crops that cannot improve the sampled base are omitted. */
internal fun ViewerViewportGeometry.detailRequest(vector: Boolean): ViewerDetailRegion? =
    if (vector) {
        vectorDetailRegion()?.let {
            val screenEdge = maxOf(it.width, it.height) * sourcePixelScale
            val edge = ceil(screenEdge).coerceIn(1.0, ViewerSvgDecoder.MAX_VIEWPORT_EDGE.toDouble()).toInt()
            ViewerDetailRegion.Vector(it, edge)
        }
    } else {
        detailRegion()?.let {
            val edge = rasterDetailEdge(it, sourcePixelScale)
            val target = MediaPipeline.targetDimensions(it.width, it.height, edge)
            if (rasterDetailImprovesPreview(sourceWidth, sourceHeight, it, target.first, target.second)) {
                ViewerDetailRegion.Raster(it, edge)
            } else {
                null
            }
        }
    }

/** A crop must preserve both base sampling densities and strictly improve at least one source axis. */
internal fun rasterDetailImprovesPreview(
    sourceWidth: Int,
    sourceHeight: Int,
    region: MediaImageRegion,
    pixelWidth: Int,
    pixelHeight: Int,
): Boolean {
    if (!region.validFor(sourceWidth, sourceHeight) || pixelWidth <= 0 || pixelHeight <= 0) return false
    val base = MediaPipeline.targetDimensions(sourceWidth, sourceHeight, MediaPipeline.VIEWER_MAX_EDGE_PX)
    val detailX = minOf(pixelWidth, region.width).toLong() * sourceWidth
    val detailY = minOf(pixelHeight, region.height).toLong() * sourceHeight
    val baseX = base.first.toLong() * region.width
    val baseY = base.second.toLong() * region.height
    return detailX >= baseX && detailY >= baseY && (detailX > baseX || detailY > baseY)
}

/** Screen demand stays inside the existing output edge and power-of-two intermediate allocation ceilings. */
private fun rasterDetailEdge(
    region: MediaImageRegion,
    pixelScale: Float,
): Int {
    val maximum = maxOf(region.width, region.height)
    var edge =
        ceil(maximum.toDouble() * pixelScale)
            .coerceIn(1.0, MediaDecodePolicy.MAX_EDGE.toDouble())
            .toInt()
            .coerceAtMost(maximum)
    var samplingBoundary = maximum
    while (MediaDecodePolicy.regionSampleSize(region, edge) == null) {
        samplingBoundary /= 2
        edge = minOf(edge, samplingBoundary)
    }
    return edge
}
