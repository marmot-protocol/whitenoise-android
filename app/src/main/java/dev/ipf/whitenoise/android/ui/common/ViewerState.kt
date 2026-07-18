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

internal fun clampViewerScale(scale: Float): Float = scale.coerceIn(VIEWER_MIN_SCALE, VIEWER_MAX_SCALE)

internal fun applyViewerPinchZoom(
    currentScale: Float,
    zoomFactor: Float,
): Float = clampViewerScale(currentScale * zoomFactor)

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
    if (imageWidth <= 0 || imageHeight <= 0) {
        return ViewerFitGeometry(viewportWidth, viewportHeight)
    }
    val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
    val viewportAspect = viewportWidth / viewportHeight
    return if (imageAspect > viewportAspect) {
        ViewerFitGeometry(viewportWidth, viewportWidth / imageAspect)
    } else {
        ViewerFitGeometry(viewportHeight * imageAspect, viewportHeight)
    }
}

internal fun viewerPanExtents(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
): Pair<Float, Float> {
    val fit = viewerFitGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight)
    val maxX = ((fit.baseWidth * scale) - viewportWidth).coerceAtLeast(0f) / 2f
    val maxY = ((fit.baseHeight * scale) - viewportHeight).coerceAtLeast(0f) / 2f
    return maxX to maxY
}

internal fun clampViewerPanOffset(
    currentOffset: Offset,
    panDelta: Offset,
    maxX: Float,
    maxY: Float,
): Offset =
    Offset(
        (currentOffset.x + panDelta.x).coerceIn(-maxX, maxX),
        (currentOffset.y + panDelta.y).coerceIn(-maxY, maxY),
    )

internal fun applyViewerTransformGesture(
    current: ViewerTransform,
    zoomFactor: Float,
    panDelta: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
): ViewerTransform {
    val nextScale = applyViewerPinchZoom(current.scale, zoomFactor)
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
            clampViewerPanOffset(current.offset, panDelta, maxX, maxY)
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
): Float {
    val fit = viewerFitGeometry(viewportWidth, viewportHeight, imageWidth, imageHeight)
    return maxOf(
        imageWidth / fit.baseWidth,
        imageHeight / fit.baseHeight,
    ).let(::clampViewerScale)
}
