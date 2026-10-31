package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ViewerTransform
import dev.ipf.whitenoise.android.ui.common.applyViewerTransformGesture
import dev.ipf.whitenoise.android.ui.common.resetViewerTransform
import dev.ipf.whitenoise.android.ui.common.viewerFitGeometry
import dev.ipf.whitenoise.android.ui.common.viewerOneToOneScale
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val VIEWER_PERCENT = 100

/** Shared image presentation; acquisition, decoded resources and transform lifetime stay with the source. */
@Composable
// Compose naming and one shared viewport keep the complete transform interaction together.
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
internal fun ImageViewerViewport(
    sourceKey: Any,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    onChromeToggle: () -> Unit,
    onViewportChange: (ViewerViewportGeometry) -> Unit = {},
    overlay: @Composable BoxScope.(ViewerViewportGeometry) -> Unit = {},
    image: @Composable BoxScope.(Modifier) -> Unit,
) {
    var viewportSize by remember(sourceKey) { mutableStateOf(IntSize.Zero) }
    val displayTransform =
        applyViewerTransformGesture(
            ViewerTransform(scale, offset),
            1f,
            Offset.Zero,
            viewportSize.width.toFloat(),
            viewportSize.height.toFloat(),
            imageWidth,
            imageHeight,
            maxScale = IMAGE_VIEWER_MAX_SCALE,
        )
    val geometry =
        ViewerViewportGeometry(
            viewportSize.width,
            viewportSize.height,
            imageWidth,
            imageHeight,
            displayTransform.scale,
            displayTransform.offset,
        )
    SideEffect { onViewportChange(geometry) }
    // Pointer coroutines survive recomposition; read current geometry and callbacks during every gesture.
    val latestScale by rememberUpdatedState(displayTransform.scale)
    val latestOffset by rememberUpdatedState(displayTransform.offset)
    val latestWidth by rememberUpdatedState(imageWidth)
    val latestHeight by rememberUpdatedState(imageHeight)
    val latestOnScaleChange by rememberUpdatedState(onScaleChange)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    val latestOnChromeToggle by rememberUpdatedState(onChromeToggle)
    val reset = {
        val fitted = resetViewerTransform()
        latestOnScaleChange(fitted.scale)
        latestOnOffsetChange(fitted.offset)
    }
    val chromeLabel = stringResource(R.string.media_viewer_toggle_controls)
    val resetLabel = stringResource(R.string.media_viewer_fit_image)
    val oneToOneLabel = stringResource(R.string.media_viewer_actual_size)
    val zoomOutLabel = stringResource(R.string.media_viewer_zoom_out)
    val zoomLabel = stringResource(R.string.media_viewer_zoom_image)

    fun applyZoom(factor: Float) {
        val next =
            applyViewerTransformGesture(
                ViewerTransform(latestScale, latestOffset),
                factor,
                Offset.Zero,
                viewportSize.width.toFloat(),
                viewportSize.height.toFloat(),
                latestWidth,
                latestHeight,
                maxScale = IMAGE_VIEWER_MAX_SCALE,
                centroid = Offset(viewportSize.width / 2f, viewportSize.height / 2f),
            )
        latestOnScaleChange(next.scale)
        latestOnOffsetChange(next.offset)
    }
    LaunchedEffect(viewportSize, imageWidth, imageHeight) { applyZoom(1f) }
    val zoomState =
        stringResource(
            R.string.media_viewer_image_scale,
            (displayTransform.scale * VIEWER_PERCENT).roundToInt(),
        )
    val imageModifier =
        Modifier
            .fillMaxSize()
            .pointerInput(sourceKey) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break
                        val currentScale = latestScale
                        val currentOffset = latestOffset
                        if (pressedCount >= 2 || currentScale > 1f) {
                            val next =
                                applyViewerTransformGesture(
                                    current = ViewerTransform(currentScale, currentOffset),
                                    zoomFactor = event.calculateZoom(),
                                    panDelta = event.calculatePan(),
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat(),
                                    imageWidth = latestWidth,
                                    imageHeight = latestHeight,
                                    maxScale = IMAGE_VIEWER_MAX_SCALE,
                                    centroid = event.calculateCentroid(useCurrent = false),
                                )
                            if (next.scale != currentScale) latestOnScaleChange(next.scale)
                            if (next.offset != currentOffset) latestOnOffsetChange(next.offset)
                            event.changes.forEach { it.consume() }
                        }
                    } while (true)
                }
            }.graphicsLayer(
                scaleX = displayTransform.scale,
                scaleY = displayTransform.scale,
                translationX = displayTransform.offset.x,
                translationY = displayTransform.offset.y,
            ).layout { measurable, constraints ->
                // The sampled bitmap's rounded dimensions do not define the original image's aspect ratio.
                // Subpixel fit dimensions stay fractional even when Compose must measure at least one pixel.
                val fit =
                    viewerFitGeometry(
                        constraints.maxWidth.toFloat(),
                        constraints.maxHeight.toFloat(),
                        imageWidth,
                        imageHeight,
                    )
                val measuredWidth = ceil(fit.baseWidth).toInt().coerceAtLeast(1)
                val measuredHeight = ceil(fit.baseHeight).toInt().coerceAtLeast(1)
                val content = measurable.measure(Constraints.fixed(measuredWidth, measuredHeight))
                val left = (constraints.maxWidth - fit.baseWidth) / 2f
                val top = (constraints.maxHeight - fit.baseHeight) / 2f
                val x = left.toInt()
                val y = top.toInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    content.placeWithLayer(x, y) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = left - x
                        translationY = top - y
                        scaleX = fit.baseWidth / measuredWidth
                        scaleY = fit.baseHeight / measuredHeight
                    }
                }
            }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .viewerTapGestureModifier(sourceKey, { latestOnChromeToggle() }, reset)
            .semantics {
                stateDescription = zoomState
                customActions =
                    listOf(
                        CustomAccessibilityAction(chromeLabel) {
                            latestOnChromeToggle()
                            true
                        },
                        CustomAccessibilityAction(zoomLabel) {
                            applyZoom(2f)
                            true
                        },
                        CustomAccessibilityAction(zoomOutLabel) {
                            applyZoom(0.5f)
                            true
                        },
                        CustomAccessibilityAction(oneToOneLabel) {
                            val desired =
                                viewerOneToOneScale(
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    latestWidth,
                                    latestHeight,
                                    IMAGE_VIEWER_MAX_SCALE,
                                )
                            applyZoom(desired / latestScale)
                            true
                        },
                        CustomAccessibilityAction(resetLabel) {
                            reset()
                            true
                        },
                    )
            }.testTag(MEDIA_VIEWER_PAGE_GESTURE_TAG),
    ) {
        image(imageModifier)
        overlay(geometry)
    }
}
