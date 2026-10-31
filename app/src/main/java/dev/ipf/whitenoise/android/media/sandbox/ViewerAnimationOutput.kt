package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_CANVAS_PIXELS
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_EDGE_PX

/** Exact source-sized viewer output; preview sessions retain their existing edge budget. */
internal data class ViewerAnimationOutput private constructor(
    val width: Int,
    val height: Int,
) {
    init {
        require(width in 1..MAX_ANIMATION_SOURCE_EDGE_PX && height in 1..MAX_ANIMATION_SOURCE_EDGE_PX)
        require(width.toLong() * height <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS)
    }

    val capacityBytes: Int = (width.toLong() * height * Int.SIZE_BYTES).toInt()

    fun matches(
        width: Int,
        height: Int,
    ): Boolean = this.width == width && this.height == height

    companion object {
        fun fromDimensions(
            width: Int,
            height: Int,
        ): ViewerAnimationOutput? =
            if (width in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
                height in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
                width.toLong() * height <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS
            ) {
                ViewerAnimationOutput(width, height)
            } else {
                null
            }

        fun fromAdmission(admission: AnimationSourceAdmission): ViewerAnimationOutput? =
            (admission as? AnimationSourceAdmission.Admitted)?.let {
                fromDimensions(it.canvasWidth, it.canvasHeight)
            }
    }
}

/** Enforces the session's negotiated canvas before allocating any frame from untrusted metadata. */
internal fun animationFrameByteCount(
    width: Int,
    height: Int,
    edge: Int,
    capacity: Int,
    viewerOutput: ViewerAnimationOutput?,
): Int? {
    val sourceValid = ViewerAnimationOutput.fromDimensions(width, height) != null
    val sessionValid =
        if (viewerOutput == null) {
            edge in 1..AnimationFrameProtocol.MAX_EDGE
        } else {
            viewerOutput.matches(width, height) && capacity == viewerOutput.capacityBytes
        }
    return if (sourceValid && sessionValid) MediaDecodePolicy.pixelBytes(width, height, edge, capacity) else null
}
