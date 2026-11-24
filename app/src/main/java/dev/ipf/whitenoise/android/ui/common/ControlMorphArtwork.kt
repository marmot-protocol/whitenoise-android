package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/** The same native-size pen decoration sits at the center of each moving outline. */
internal fun DrawScope.drawControlMorphPen(
    bounds: Rect,
    painter: Painter,
    tint: Color,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val iconSize = 24.dp.toPx()
    translate(bounds.center.x - iconSize / 2f, bounds.center.y - iconSize / 2f) {
        with(painter) { draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(tint)) }
    }
}
