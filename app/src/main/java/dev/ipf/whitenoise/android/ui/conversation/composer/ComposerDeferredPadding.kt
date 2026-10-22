package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Reads animated composer insets during measurement, preserving unbounded parent dimensions.
 * Subtracting from Constraints.Infinity would turn the sentinel into an unrepresentable finite size.
 */
internal fun Modifier.deferredPadding(
    start: () -> Dp = { 0.dp },
    top: () -> Dp = { 0.dp },
    end: () -> Dp = { 0.dp },
    bottom: () -> Dp = { 0.dp },
): Modifier =
    layout { measurable, constraints ->
        val startPx = start().roundToPx()
        val topPx = top().roundToPx()
        val endPx = end().roundToPx()
        val bottomPx = bottom().roundToPx()
        val horizontal = startPx + endPx
        val vertical = topPx + bottomPx
        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
        val width = constraints.constrainWidth(placeable.width + horizontal)
        val height = constraints.constrainHeight(placeable.height + vertical)
        layout(width, height) {
            placeable.placeRelative(startPx, topPx)
        }
    }
