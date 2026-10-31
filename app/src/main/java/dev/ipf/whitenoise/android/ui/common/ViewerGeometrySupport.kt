package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent

/** Finite bounds and focal geometry shared by the existing viewer transform operations. */
internal fun positiveFinite(value: Float): Boolean = value.isFinite() && value > 0f

internal fun validViewerScale(value: Float): Boolean = value.isFinite() && value >= VIEWER_MIN_SCALE

internal fun Offset.isFiniteOffset(): Boolean = x.isFinite() && y.isFinite()

internal fun finiteViewport(
    width: Float,
    height: Float,
): Boolean = width.isFinite() && height.isFinite()

internal fun validViewport(
    width: Float,
    height: Float,
): Boolean = positiveFinite(width) && positiveFinite(height)

internal fun validPanExtents(
    maxX: Float,
    maxY: Float,
): Boolean = finiteViewport(maxX, maxY) && maxX >= 0f && maxY >= 0f

internal fun validCentroid(centroid: Offset?): Boolean = centroid == null || centroid.isFiniteOffset()

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

/** Stationary one-finger samples belong to tap detection; edge pan attempts still own their input. */
internal fun shouldConsumeViewerTransform(
    pressedCount: Int,
    zoom: Float,
    pan: Offset,
): Boolean = pressedCount >= 2 || zoom != 1f || pan != Offset.Zero

internal fun PointerEvent.consumeViewerTransformIfNeeded(
    pressedCount: Int,
    zoom: Float,
    pan: Offset,
) {
    if (shouldConsumeViewerTransform(pressedCount, zoom, pan)) changes.forEach { it.consume() }
}
