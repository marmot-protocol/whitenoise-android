package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private object ConnectedRowBorderDefaults {
    /** Compose rounds a 1 dp border up to whole pixels, matching the existing AMOLED surface edge. */
    val PerimeterWidth = 1.dp

    /** The divider shared by two touching rows is always exactly one physical pixel. */
    const val SeamWidthPx = 1f

    /** Every rounded corner is a quarter turn, starting from these compass angles. */
    const val QuarterTurnDegrees = 90f
    const val TopLeftStartDegrees = 180f
    const val TopRightStartDegrees = 270f
    const val BottomRightStartDegrees = 0f
    const val BottomLeftStartDegrees = 90f
}

/** Positional Material corners, with the preceding row owning the shared divider. */
internal data class ConnectedRowShape(
    val corners: Shape,
    val first: Boolean,
    val last: Boolean,
) : Shape {
    /** Delegate RTL-aware Material corner resolution without altering its dimensions. */
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = corners.createOutline(size, layoutDirection, density)
}

/**
 * Native-weight perimeter with one physical-pixel divider, including independently lazy rows.
 *
 * Each row strokes its own side edges in [color]. Only the first row strokes the top and only the last
 * row strokes the bottom, while every non-last row paints the seam below it in [seamColor], so touching
 * rows never double their shared edge the way per-row outlines did in the existing AMOLED group, and the
 * inner dividers can sit quieter than the group's outline.
 */
@Suppress("LongMethod")
internal fun Modifier.connectedRowBorder(
    shape: ConnectedRowShape,
    color: Color,
    seamColor: Color = color,
    suppressChrome: () -> Boolean = { false },
    suppressSeam: () -> Boolean = suppressChrome,
): Modifier =
    drawWithCache {
        val corners =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect
                is Outline.Rectangle -> RoundRect(outline.rect)
                // Cut or path corners are not used by the production shape scheme, fall back to square bounds.
                is Outline.Generic -> RoundRect(outline.bounds)
            }
        // Match Compose's pixel-rounded 1 dp border and inset its full width inside the shape.
        val strokeWidth = ceil(ConnectedRowBorderDefaults.PerimeterWidth.toPx())
        val inset = strokeWidth / 2
        val left = inset
        val right = size.width - inset
        val path = connectedRowBorderPath(Rect(Offset.Zero, size), corners, shape.first, shape.last, strokeWidth)
        val seamWidth = ConnectedRowBorderDefaults.SeamWidthPx
        val seamCenterY = size.height - seamWidth / 2
        onDrawWithContent {
            drawContent()
            if (!shape.last && !suppressSeam()) {
                drawLine(seamColor, Offset(left, seamCenterY), Offset(right, seamCenterY), strokeWidth = seamWidth)
            }
            if (!suppressChrome()) drawPath(path, color, style = Stroke(width = strokeWidth))
        }
    }

/** Shared native path: preserve continuous corner/side joins and the original arc rasterization. */
@Suppress("LongMethod")
internal fun connectedRowBorderPath(
    bounds: Rect,
    corners: RoundRect,
    first: Boolean,
    last: Boolean,
    strokeWidth: Float,
): Path {
    val inset = strokeWidth / 2
    val left = bounds.left + inset
    val right = bounds.right - inset
    val top = bounds.top + inset
    val bottom = bounds.bottom - inset
    val topLeft = (corners.topLeftCornerRadius.x - inset).coerceAtLeast(0f)
    val topRight = (corners.topRightCornerRadius.x - inset).coerceAtLeast(0f)
    val bottomRight = (corners.bottomRightCornerRadius.x - inset).coerceAtLeast(0f)
    val bottomLeft = (corners.bottomLeftCornerRadius.x - inset).coerceAtLeast(0f)
    return Path().apply {
        if (first) {
            moveTo(left, top + topLeft)
            cornerArc(
                Rect(left, top, left + 2 * topLeft, top + 2 * topLeft),
                ConnectedRowBorderDefaults.TopLeftStartDegrees,
            )
            lineTo(right - topRight, top)
            cornerArc(
                Rect(right - 2 * topRight, top, right, top + 2 * topRight),
                ConnectedRowBorderDefaults.TopRightStartDegrees,
            )
        } else {
            moveTo(right, bounds.top)
        }
        if (last) {
            lineTo(right, bottom - bottomRight)
            cornerArc(
                Rect(right - 2 * bottomRight, bottom - 2 * bottomRight, right, bottom),
                ConnectedRowBorderDefaults.BottomRightStartDegrees,
            )
            lineTo(left + bottomLeft, bottom)
            cornerArc(
                Rect(left, bottom - 2 * bottomLeft, left + 2 * bottomLeft, bottom),
                ConnectedRowBorderDefaults.BottomLeftStartDegrees,
            )
        } else {
            lineTo(right, bounds.bottom)
            moveTo(left, bounds.bottom)
        }
        lineTo(left, if (first) top + topLeft else bounds.top)
        if (first && last) close()
    }
}

/** Append one quarter-turn corner; a zero radius corner is a plain vertex and draws nothing. */
private fun Path.cornerArc(
    rect: Rect,
    startAngleDegrees: Float,
) {
    if (rect.width > 0f) {
        arcTo(
            rect = rect,
            startAngleDegrees = startAngleDegrees,
            sweepAngleDegrees = ConnectedRowBorderDefaults.QuarterTurnDegrees,
            forceMoveTo = false,
        )
    }
}
