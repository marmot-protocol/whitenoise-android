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
