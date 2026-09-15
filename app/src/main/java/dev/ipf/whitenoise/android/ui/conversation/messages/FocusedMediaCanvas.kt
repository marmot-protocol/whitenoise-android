package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

private const val FOCUSED_VISUAL_CANVAS_SCALE = 0.75f

/** Constrains the artwork canvas before native controls and metadata are measured at their normal text size. */
internal fun focusedVisualCanvasSize(
    source: IntSize,
    maximumWidth: Int,
    maximumHeight: Int,
): IntSize {
    if (source.width <= 0 || source.height <= 0) return IntSize.Zero
    val factor =
        minOf(
            FOCUSED_VISUAL_CANVAS_SCALE,
            maximumWidth.coerceAtLeast(0).toFloat() / source.width,
            maximumHeight.coerceAtLeast(0).toFloat() / source.height,
        )
    return IntSize((source.width * factor).roundToInt(), (source.height * factor).roundToInt())
}

/**
 * Uses this viewport's normal lookahead measurement, then remeasures the same visual owner at75%.
 * The containing media group supplies LookaheadScope; ordinary standalone frames need no scope.
 */
internal fun focusedVisualCanvasModifier(focused: Boolean): Modifier =
    if (!focused) {
        Modifier
    } else {
        Modifier.approachLayout(isMeasurementApproachInProgress = { true }) { measurable, constraints ->
            val canvas = focusedVisualCanvasSize(lookaheadSize, constraints.maxWidth, constraints.maxHeight)
            val child =
                measurable.measure(
                    constraints.copy(minWidth = 0, maxWidth = canvas.width, minHeight = 0, maxHeight = canvas.height),
                )
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
    }

/**
 * Keeps the current viewport's normal timeline footprint while capturing its one focused media tree.
 * Lookahead measures the actual native layouts, including file wrapping and portrait/grid sizing.
 */
internal fun Modifier.focusedMediaFootprint(
    focused: Boolean,
    maximumPreviewWidth: Int,
    mine: Boolean,
): Modifier =
    approachLayout(isMeasurementApproachInProgress = { focused }) { measurable, constraints ->
        val childConstraints =
            if (focused) {
                constraints.copy(minWidth = 0, maxWidth = minOf(constraints.maxWidth, maximumPreviewWidth))
            } else {
                constraints
            }
        val child = measurable.measure(childConstraints)
        val width =
            (if (focused) lookaheadSize.width else child.width)
                .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            (if (focused) lookaheadSize.height else child.height)
                .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { child.placeRelative(if (mine) width - child.width else 0, 0) }
    }
