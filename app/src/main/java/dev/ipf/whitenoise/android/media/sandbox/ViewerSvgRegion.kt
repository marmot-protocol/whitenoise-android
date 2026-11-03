package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal const val VIEWER_SVG_SOURCE_EDGE = 8192
private const val MIN_VIEWER_SVG_EXTENT = 0.000001
private const val SVG_BOTTOM_COMPONENT_INDEX = 3
private const val MAX_VIEWER_SVG_PIXELS = 4_194_304L

/** Fractional original-document units; raster source-pixel regions remain integers. */
internal data class ViewerSvgRegion(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    /** The sub-micro-unit floor bounds Canvas transforms, well below a 1-unit document at 1024x zoom. */
    fun validFor(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Boolean =
        left.isFinite() &&
            top.isFinite() &&
            right.isFinite() &&
            bottom.isFinite() &&
            left >= 0.0 &&
            top >= 0.0 &&
            right <= sourceWidth &&
            bottom <= sourceHeight &&
            width >= MIN_VIEWER_SVG_EXTENT &&
            height >= MIN_VIEWER_SVG_EXTENT
}

/** Reject omitted or substituted primitive types rather than using Bundle's zero/default conversions. */
@Suppress("DEPRECATION") // Raw primitive-type validation precedes any typed Bundle read.
internal fun readViewerSvgRegion(data: Bundle): ViewerSvgRegion {
    val names = listOf("svgLeft", "svgTop", "svgRight", "svgBottom")
    val values = names.map { name -> data.get(name).also { require(it is Double) } as Double }
    return ViewerSvgRegion(values[0], values[1], values[2], values[SVG_BOTTOM_COMPONENT_INDEX]).also {
        require(it.validFor(VIEWER_SVG_SOURCE_EDGE, VIEWER_SVG_SOURCE_EDGE))
    }
}

/** A rectangular SVG output budget shared by the host transport and permissionless renderer. */
internal data class ViewerSvgTarget(
    val width: Int,
    val height: Int,
) {
    val capacity: Int get() = width * height * Int.SIZE_BYTES
}

/**
 * Covers the requested long edge without rounding away a screen pixel, then bounds oversized
 * viewports to 4 Mpx (16 MiB ARGB). The cap applies to the rectangle, never an edge-squared buffer.
 * Invalid source coordinates and output edges fail before any shared-memory or bitmap allocation.
 */
internal fun ViewerSvgRegion.targetSize(edge: Int): ViewerSvgTarget? {
    if (edge !in 1..MediaDecodePolicy.MAX_EDGE || !validFor(VIEWER_SVG_SOURCE_EDGE, VIEWER_SVG_SOURCE_EDGE)) return null
    val ratio = edge.toDouble() / maxOf(width, height)
    var pixelsWide = ceil(width * ratio).toInt().coerceIn(1, edge)
    var pixelsHigh = ceil(height * ratio).toInt().coerceIn(1, edge)
    val pixels = pixelsWide.toLong() * pixelsHigh
    if (pixels > MAX_VIEWER_SVG_PIXELS) {
        val factor = sqrt(MAX_VIEWER_SVG_PIXELS.toDouble() / pixels)
        pixelsWide = floor(pixelsWide * factor).toInt().coerceAtLeast(1)
        pixelsHigh = floor(pixelsHigh * factor).toInt().coerceAtLeast(1)
    }
    return ViewerSvgTarget(pixelsWide, pixelsHigh).takeIf {
        pixelsWide.toLong() * pixelsHigh <= MAX_VIEWER_SVG_PIXELS
    }
}
