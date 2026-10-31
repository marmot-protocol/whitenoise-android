package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Offset

internal const val VIEWER_MIN_SCALE = 1f

internal const val VIEWER_MAX_SCALE = 5f

internal data class ViewerTransform(
    val scale: Float,
    val offset: Offset,
)

internal data class AvatarDragDismissState(
    val draggedDownPx: Float = 0f,
)

internal sealed interface AvatarDragDismissResult {
    data object Ignored : AvatarDragDismissResult

    data class Tracking(
        val state: AvatarDragDismissState,
    ) : AvatarDragDismissResult

    data class Dismiss(
        val state: AvatarDragDismissState,
    ) : AvatarDragDismissResult
}

internal fun clampViewerPageIndex(
    requestedIndex: Int,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    return requestedIndex.coerceIn(0, pageCount - 1)
}

internal fun clampViewerScale(
    scale: Float,
    maxScale: Float = VIEWER_MAX_SCALE,
): Float {
    val ceiling = maxScale.takeIf { it.isFinite() && it >= VIEWER_MIN_SCALE } ?: VIEWER_MAX_SCALE
    return if (scale.isFinite()) scale.coerceIn(VIEWER_MIN_SCALE, ceiling) else VIEWER_MIN_SCALE
}

internal fun applyViewerPinchZoom(
    currentScale: Float,
    zoomFactor: Float,
    maxScale: Float = VIEWER_MAX_SCALE,
): Float {
    if (!currentScale.isFinite() || !zoomFactor.isFinite() || zoomFactor <= 0f) return VIEWER_MIN_SCALE
    val ceiling = maxScale.takeIf { it.isFinite() && it >= VIEWER_MIN_SCALE } ?: VIEWER_MAX_SCALE
    return (currentScale.toDouble() * zoomFactor).coerceIn(VIEWER_MIN_SCALE.toDouble(), ceiling.toDouble()).toFloat()
}

internal fun viewerPagerScrollEnabled(scale: Float): Boolean = scale <= VIEWER_MIN_SCALE

internal fun resetViewerTransform(): ViewerTransform = ViewerTransform(VIEWER_MIN_SCALE, Offset.Zero)

internal data class ViewerFitGeometry(
    val baseWidth: Float,
    val baseHeight: Float,
)

internal fun viewerFitGeometry(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): ViewerFitGeometry {
    if (!validViewport(viewportWidth, viewportHeight)) {
        return ViewerFitGeometry(0f, 0f)
    }
    return if (imageWidth <= 0 || imageHeight <= 0) {
        ViewerFitGeometry(viewportWidth, viewportHeight)
    } else {
        fitKnownViewerImage(viewportWidth, viewportHeight, imageWidth, imageHeight)
    }
}

internal fun viewerPanExtents(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
): Pair<Float, Float> {
    if (!validViewerScale(scale) || !finiteViewport(viewportWidth, viewportHeight)) {
        return 0f to 0f
    }
    val fit = viewerFitGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight)
    val maxX =
        (((fit.baseWidth.toDouble() * scale) - viewportWidth) / 2)
            .coerceIn(
                0.0,
                Float.MAX_VALUE.toDouble(),
            ).toFloat()
    val maxY =
        (((fit.baseHeight.toDouble() * scale) - viewportHeight) / 2)
            .coerceIn(
                0.0,
                Float.MAX_VALUE.toDouble(),
            ).toFloat()
    return maxX to maxY
}

internal fun clampViewerPanOffset(
    currentOffset: Offset,
    panDelta: Offset,
    maxX: Float,
    maxY: Float,
): Offset =
    if (!currentOffset.isFiniteOffset() || !panDelta.isFiniteOffset() || !validPanExtents(maxX, maxY)) {
        Offset.Zero
    } else {
        Offset(
            (currentOffset.x.toDouble() + panDelta.x).coerceIn(-maxX.toDouble(), maxX.toDouble()).toFloat(),
            (currentOffset.y.toDouble() + panDelta.y).coerceIn(-maxY.toDouble(), maxY.toDouble()).toFloat(),
        )
    }

internal fun applyViewerTransformGesture(
    current: ViewerTransform,
    zoomFactor: Float,
    panDelta: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    maxScale: Float = VIEWER_MAX_SCALE,
    centroid: Offset? = null,
): ViewerTransform {
    val validState = validViewerScale(current.scale) && current.offset.isFiniteOffset()
    val validGesture = positiveFinite(zoomFactor) && panDelta.isFiniteOffset() && validCentroid(centroid)
    val validBounds = validViewport(viewportWidth, viewportHeight) && validViewerScale(maxScale)
    if (!validState || !validGesture || !validBounds) return resetViewerTransform()
    val nextScale = applyViewerPinchZoom(current.scale, zoomFactor, maxScale)
    val ratio = nextScale / current.scale.coerceAtLeast(VIEWER_MIN_SCALE)
    val anchoredOffset = viewerAnchoredOffset(current.offset, centroid, viewportWidth, viewportHeight, ratio)
    val nextOffset =
        if (nextScale > VIEWER_MIN_SCALE) {
            val (maxX, maxY) =
                viewerPanExtents(
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    scale = nextScale,
                )
            clampViewerPanOffset(anchoredOffset, panDelta, maxX, maxY)
        } else if (current.offset != Offset.Zero) {
            Offset.Zero
        } else {
            current.offset
        }
    return ViewerTransform(nextScale, nextOffset)
}

internal fun applyAvatarDownwardDrag(
    scale: Float,
    state: AvatarDragDismissState,
    dragAmount: Float,
    dismissThresholdPx: Float,
): AvatarDragDismissResult {
    if (scale > VIEWER_MIN_SCALE) return AvatarDragDismissResult.Ignored
    if (dragAmount <= 0f && state.draggedDownPx <= 0f) {
        return AvatarDragDismissResult.Ignored
    }
    val nextDragged = (state.draggedDownPx + dragAmount).coerceAtLeast(0f)
    val nextState = AvatarDragDismissState(nextDragged)
    return if (nextDragged >= dismissThresholdPx) {
        AvatarDragDismissResult.Dismiss(AvatarDragDismissState(0f))
    } else {
        AvatarDragDismissResult.Tracking(nextState)
    }
}

internal fun viewerOneToOneScale(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    maxScale: Float = VIEWER_MAX_SCALE,
): Float {
    val fit = viewerFitGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight)
    if (fit.baseWidth <= 0f || fit.baseHeight <= 0f) return VIEWER_MIN_SCALE
    return maxOf(
        imageWidth / fit.baseWidth,
        imageHeight / fit.baseHeight,
    ).let { clampViewerScale(it, maxScale) }
}

private fun positiveFinite(value: Float): Boolean = value.isFinite() && value > 0f

private fun validViewerScale(value: Float): Boolean = value.isFinite() && value >= VIEWER_MIN_SCALE

private fun Offset.isFiniteOffset(): Boolean = x.isFinite() && y.isFinite()

private fun finiteViewport(
    width: Float,
    height: Float,
): Boolean = width.isFinite() && height.isFinite()

private fun validViewport(
    width: Float,
    height: Float,
): Boolean = positiveFinite(width) && positiveFinite(height)

private fun validPanExtents(
    maxX: Float,
    maxY: Float,
): Boolean = finiteViewport(maxX, maxY) && maxX >= 0f && maxY >= 0f

private fun validCentroid(centroid: Offset?): Boolean = centroid == null || centroid.isFiniteOffset()

private fun viewerAnchoredOffset(
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

private fun fitKnownViewerImage(
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
