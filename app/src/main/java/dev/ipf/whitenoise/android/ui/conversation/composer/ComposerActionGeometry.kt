package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val COMPOSER_ACTION_CENTER_BIAS = 0.5f
internal val ComposerActionRowHeight = 48.dp

/** The shared placement boundary used by both action clusters and their editor viewport. */
internal fun composerActionRowTop(
    spaceHeight: Int,
    rowHeight: Int,
    editingProgress: Float,
): Int {
    val slack = (spaceHeight - rowHeight).coerceAtLeast(0)
    val bias = COMPOSER_ACTION_CENTER_BIAS + (1f - COMPOSER_ACTION_CENTER_BIAS) * editingProgress.coerceIn(0f, 1f)
    return (slack * bias).roundToInt()
}

/** Keep the protected inline editor until at least one complete line fits above the moving toolbar. */
internal fun composerEditorViewportHeight(
    allocatedHeight: Int,
    heightAboveActions: Int,
    lineHeight: Int,
): Int =
    if (heightAboveActions >= lineHeight.coerceAtLeast(1)) {
        allocatedHeight.coerceAtMost(heightAboveActions.coerceAtLeast(0))
    } else {
        allocatedHeight
    }

/**
 * Vertical placement for the composer's action clusters, inline and trailing alike: centered with the text
 * in the resting one-line row and pinned to the bottom of the editing row, blending between the two with the
 * animated editing progress. The progress is read during placement so nothing recomposes per frame.
 */
internal class ComposerActionRowAlignment(
    private val horizontal: Alignment.Horizontal,
    private val editingProgress: () -> Float,
) : Alignment {
    /** Places the cluster at the horizontal edge and at the progress-weighted vertical bias. */
    override fun align(
        size: IntSize,
        space: IntSize,
        layoutDirection: LayoutDirection,
    ): IntOffset {
        val x = horizontal.align(size.width, space.width, layoutDirection)
        return IntOffset(x, composerActionRowTop(space.height, size.height, editingProgress()))
    }
}

/** Keeps the trailing native action owner inside the shared 48dp composer toolbar. */
internal fun Modifier.expandedComposerActionRow(): Modifier =
    layout { measurable, constraints ->
        val rowHeight = ComposerActionRowHeight.roundToPx().coerceAtMost(constraints.maxHeight)
        val endInset = 4.dp.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(minHeight = rowHeight, maxHeight = rowHeight),
            )
        layout(placeable.width + endInset, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

internal val ComposerToolbarSlotWidth = 40.dp
