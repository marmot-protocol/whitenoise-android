package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent

/** Accepts positive finite dimensions; zero, negative values and infinities are invalid. */
internal fun positiveFinite(value: Float): Boolean = value.isFinite() && value > 0f

/** Accepts finite scales at or above the viewer minimum, including the fitted scale. */
internal fun validViewerScale(value: Float): Boolean = value.isFinite() && value >= VIEWER_MIN_SCALE

/** Requires both offset components to be finite before applying a transform. */
internal fun Offset.isFiniteOffset(): Boolean = x.isFinite() && y.isFinite()

/** Checks finiteness only; callers that require positive dimensions use [validViewport]. */
internal fun finiteViewport(
    width: Float,
    height: Float,
): Boolean = width.isFinite() && height.isFinite()

/** Requires positive finite viewport dimensions for fit and pointer geometry. */
internal fun validViewport(
    width: Float,
    height: Float,
): Boolean = positiveFinite(width) && positiveFinite(height)

/** Allows zero pan range but rejects negative or nonfinite extents. */
internal fun validPanExtents(
    maxX: Float,
    maxY: Float,
): Boolean = finiteViewport(maxX, maxY) && maxX >= 0f && maxY >= 0f

/** Allows an absent focal point, or an offset with two finite components. */
internal fun validCentroid(centroid: Offset?): Boolean = centroid == null || centroid.isFiniteOffset()

/**
 * Preserves a validated focal point while applying the new-to-old scale [ratio].
 * The centroid is viewport-local; [offset] is relative to the viewport center.
 * An absent centroid keeps [offset] unchanged.
 */
internal fun viewerAnchoredOffset(
    offset: Offset,
    centroid: Offset?,
    viewportWidth: Float,
    viewportHeight: Float,
    ratio: Float,
): Offset {
    val centered =
        (centroid ?: Offset(viewportWidth / 2f, viewportHeight / 2f)) -
            Offset(viewportWidth / 2f, viewportHeight / 2f)
    return if (centroid == null) offset else offset + (centered - offset) * (1f - ratio)
}

/** Aspect-fits positive, previously validated image and viewport dimensions without cropping. */
internal fun fitKnownViewerImage(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): ViewerFitGeometry {
    val imageAspect = imageWidth.toDouble() / imageHeight
    val viewportAspect = viewportWidth.toDouble() / viewportHeight
    return if (imageAspect > viewportAspect) {
        ViewerFitGeometry(viewportWidth, (viewportWidth / imageAspect).toFloat())
    } else {
        ViewerFitGeometry((viewportHeight * imageAspect).toFloat(), viewportHeight)
    }
}

/** Defers single-finger input ownership until slop, then retains it until this gesture ends. */
internal class ViewerGestureConsumption(
    private val touchSlop: Float,
) {
    private var pan = Offset.Zero
    private var ownsGesture = false

    /** Acquires ownership on two pointers or cumulative pan beyond slop, then keeps it for this gesture. */
    fun shouldConsume(
        pressedCount: Int,
        delta: Offset,
    ): Boolean {
        pan += delta
        ownsGesture = ownsGesture || pressedCount >= 2 || pan.getDistance() > touchSlop
        return ownsGesture
    }

    /** Consumes every pointer change only after this gesture has acquired input ownership. */
    fun consumeIfNeeded(
        event: PointerEvent,
        pressedCount: Int,
        delta: Offset,
    ) {
        if (shouldConsume(pressedCount, delta)) event.changes.forEach { it.consume() }
    }
}
