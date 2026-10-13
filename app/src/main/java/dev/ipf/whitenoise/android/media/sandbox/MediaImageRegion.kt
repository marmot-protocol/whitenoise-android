package dev.ipf.whitenoise.android.media.sandbox

/** Encoded-source coordinates, not a preview's pixels or EXIF-oriented coordinates. */
internal data class MediaImageRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun validFor(
        width: Int,
        height: Int,
    ): Boolean = left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
}
