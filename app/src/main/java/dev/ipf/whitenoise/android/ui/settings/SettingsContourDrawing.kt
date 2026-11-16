package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified

internal fun SettingsContour.drawnCorners(host: Rect): RoundRect {
    val b = bounds.translate(-host.topLeft)
    return RoundRect(
        b.left,
        b.top,
        b.right,
        b.bottom,
        corners.topLeftCornerRadius,
        corners.topRightCornerRadius,
        corners.bottomRightCornerRadius,
        corners.bottomLeftCornerRadius,
    )
}

/** Current rounded interiors own body visibility; native viewport/header clipping stays stationary. */
internal fun movingBodyMask(
    contours: List<SettingsContour>,
    host: Rect,
): Path =
    Path().apply {
        contours.forEach { contour ->
            if (!contour.bounds.isEmpty) {
                val rounded = Path().apply { addRoundRect(contour.drawnCorners(host)) }
                val viewport = Path().apply { addRect(contour.viewport.intersect(host).translate(-host.topLeft)) }
                rounded.op(rounded, viewport, PathOperation.Intersect)
                addPath(rounded)
            }
        }
    }

internal fun DrawScope.drawContourFill(
    contour: SettingsContour,
    host: Rect,
) {
    if (!contour.fill.isSpecified || contour.bounds.isEmpty) return
    val viewport = contour.viewport.intersect(host).translate(-host.topLeft)
    clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
        drawPath(Path().apply { addRoundRect(contour.drawnCorners(host)) }, contour.fill)
    }
}

/** One reveal mask keeps native headers live and separates outlined interiors from free-form body ink. */
internal fun Rect.nativeLiveBodyMask(
    coverage: Path,
    outlined: Path,
    interiors: Path,
    unoutlined: Path?,
    showBody: Boolean,
): Path =
    Path().apply {
        addRect(this@nativeLiveBodyMask)
        op(this, coverage, PathOperation.Difference)
        if (showBody) {
            val incoming =
                Path().apply {
                    if (unoutlined != null) addPath(unoutlined)
                    val clipped = Path().apply { op(interiors, outlined, PathOperation.Intersect) }
                    op(this, clipped, PathOperation.Union)
                }
            op(this, incoming, PathOperation.Union)
        }
    }
