package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FocusedStackHorizontalPadding = 16.dp

internal fun WindowInsets.actionTouchXInPaddedFrame(
    touchX: Float?,
    density: Density,
    layoutDirection: LayoutDirection,
): Float? =
    touchX?.let {
        val leftInsetPx = getLeft(density, layoutDirection)
        it - leftInsetPx - with(density) { FocusedStackHorizontalPadding.roundToPx() }
    }

/**
 * Places a stack child as if the stack were still the centred column of [columnWidth], aligned to its end
 * edge for [mine] and its start edge otherwise, while letting it measure up to [maximumWidth].
 *
 * A child no wider than the column sits exactly where the capped stack put it. A wider one keeps that edge
 * until it would leave the window, then slides back inside it, so it grows across the window rather than
 * past either side. Touch placement uses a physical X within the padded frame. Without a touch,
 * offsets are start-relative, so RTL mirrors them like the column alignment did.
 */
internal fun Modifier.focusedColumnSlot(
    columnWidth: Dp,
    maximumWidth: Dp,
    mine: Boolean,
    touchX: Float? = null,
): Modifier =
    layout { measurable, constraints ->
        val maxChild = maximumWidth.roundToPx().coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxChild))
        val width = constraints.maxWidth
        val column = columnWidth.roundToPx().coerceIn(0, width)
        val columnStart = (width - column) / 2
        val preferred = if (mine) columnStart + column - placeable.width else columnStart
        val maxLeft = width - placeable.width
        layout(width, placeable.height) {
            if (touchX == null) {
                placeable.placeRelative(preferred.coerceIn(0, maxLeft), 0)
            } else {
                val left = (touchX - placeable.width / 2f).roundToInt().coerceIn(0, maxLeft)
                placeable.place(left, 0)
            }
        }
    }
