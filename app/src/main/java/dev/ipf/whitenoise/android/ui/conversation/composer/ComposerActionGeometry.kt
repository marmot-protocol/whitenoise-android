package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp

private val ExpandedComposerActionInset = 4.dp
private val CompactComposerActionSize = 44.dp
private val ExpandedComposerActionSize = 48.dp

internal fun Modifier.expandedComposerActionRow(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val fraction = progress().coerceIn(0f, 1f)
        val actionSize =
            (
                CompactComposerActionSize +
                    (ExpandedComposerActionSize - CompactComposerActionSize) * fraction
            ).roundToPx()
        val inset = (ExpandedComposerActionInset * fraction).roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minHeight = actionSize,
                ),
            )
        layout(placeable.width + inset, placeable.height + inset) {
            placeable.placeRelative(0, 0)
        }
    }

internal fun Modifier.composerActionSize(progress: () -> Float): Modifier =
    layout { measurable, constraints ->
        val fraction = progress().coerceIn(0f, 1f)
        val size =
            (
                CompactComposerActionSize +
                    (ExpandedComposerActionSize - CompactComposerActionSize) * fraction
            ).roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = size,
                    maxWidth = size,
                    minHeight = size,
                    maxHeight = size,
                ),
            )
        layout(size, size) {
            placeable.placeRelative(0, 0)
        }
    }
