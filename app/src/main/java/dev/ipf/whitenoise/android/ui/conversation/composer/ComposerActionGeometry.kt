package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

/** Keeps the trailing native action owner inside the shared 48dp composer toolbar. */
internal fun Modifier.expandedComposerActionRow(): Modifier =
    layout { measurable, constraints ->
        val rowHeight = 48.dp.roundToPx().coerceAtMost(constraints.maxHeight)
        val endInset = 4.dp.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(minHeight = rowHeight, maxHeight = rowHeight),
            )
        layout(placeable.width + endInset, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

/** Centers the 32dp send disc in the prototype's 40dp action slot without replacing its send callback. */
internal fun Modifier.composerActionSize(): Modifier =
    layout { measurable, constraints ->
        val discSize = 32.dp.roundToPx().coerceAtMost(minOf(constraints.maxWidth, constraints.maxHeight))
        val width = 40.dp.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = 48.dp.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable =
            measurable.measure(
                constraints.copy(minWidth = discSize, maxWidth = discSize, minHeight = discSize, maxHeight = discSize),
            )
        layout(width, height) {
            placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }
