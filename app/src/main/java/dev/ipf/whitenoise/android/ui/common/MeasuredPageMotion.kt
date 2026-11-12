package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.dp

/** Shared container geometry for live routes and permitted inert reverse frames. */
internal fun measuredPageBounds(
    origin: Rect,
    host: Rect,
    presence: Float,
): Rect = lerp(origin, host, presence.coerceIn(0f, 1f))

/** Uniform width scaling keeps text proportional; the rounded container clips the growing height. */
internal fun DrawScope.drawMeasuredPage(
    bounds: Rect,
    presence: Float,
    backplate: Color = Color.Unspecified,
    sourceWidth: Float = size.width,
    content: DrawScope.() -> Unit,
) {
    val corner = CornerRadius(20.dp.toPx() * (1f - presence.coerceIn(0f, 1f)))
    val path = Path().apply { addRoundRect(RoundRect(bounds, corner)) }
    clipPath(path) {
        if (backplate.isSpecified) drawRect(backplate, topLeft = bounds.topLeft, size = bounds.size)
        withTransform({
            translate(bounds.left, bounds.top)
            val scale = if (sourceWidth > 0f) bounds.width / sourceWidth else 1f
            scale(scale, scale, Offset.Zero)
        }, content)
    }
}
