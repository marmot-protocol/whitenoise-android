package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle

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
            width >= 0.000001 &&
            height >= 0.000001
}

/** Reject omitted or substituted primitive types rather than using Bundle's zero/default conversions. */
@Suppress("DEPRECATION") // Raw primitive-type validation precedes any typed Bundle read.
internal fun readViewerSvgRegion(data: Bundle): ViewerSvgRegion {
    val names = listOf("svgLeft", "svgTop", "svgRight", "svgBottom")
    val values = names.map { name -> data.get(name).also { require(it is Double) } as Double }
    return ViewerSvgRegion(values[0], values[1], values[2], values[3]).also { require(it.validFor(8192, 8192)) }
}
