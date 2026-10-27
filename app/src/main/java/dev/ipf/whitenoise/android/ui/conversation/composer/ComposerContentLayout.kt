@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

internal const val COMPOSER_SUGGESTIONS_SLOT = "composer-suggestions"

/**
 * Keeps the final editor child usable before assigning spare height to suggestions.
 * Long drafts leave a one-row suggestion viewport where their minimum chrome fits.
 * Other children are required status chrome. They retain their ordinary column
 * order and spacing; only the optional suggestion slot is measured last. Manual
 * expansion still fills the editor's remaining height, as the weighted column did.
 */
@Composable
internal fun ComposerContentLayout(
    modifier: Modifier,
    fillEditorHeight: Boolean,
    minimumEditorHeight: Dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacing = 8.dp.roundToPx()
        val suggestions = measurables.indexOfFirst { it.layoutId == COMPOSER_SUGGESTIONS_SLOT }
        val editor = measurables.lastIndex
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var usedHeight = 0
        var visibleCount = 0

        // Required status chrome precedes the editor, with the same priority it had in Column.
        for (index in measurables.indices.filter { it != suggestions && it != editor }) {
            val gap = if (visibleCount > 0) spacing else 0
            val available = (constraints.maxHeight - usedHeight - gap).coerceAtLeast(0)
            val placeable = measurables[index].measure(constraints.copy(minHeight = 0, maxHeight = available))
            placeables[index] = placeable
            if (placeable.height > 0) {
                usedHeight += gap + placeable.height
                visibleCount++
            }
        }
        val editorGap = if (visibleCount > 0) spacing else 0
        val availableEditorHeight = (constraints.maxHeight - usedHeight - editorGap).coerceAtLeast(0)
        // 56dp list row plus the surface's 8dp padding. Short editors can leave
        // more space; long drafts retain their measured line, controls and accessories.
        val suggestionReserve =
            if (suggestions >= 0) {
                minOf(
                    64.dp.roundToPx() + spacing,
                    (availableEditorHeight - minimumEditorHeight.roundToPx()).coerceAtLeast(0),
                )
            } else {
                0
            }
        val editorHeight = availableEditorHeight - suggestionReserve
        val editorPlaceable =
            measurables[editor].measure(
                constraints.copy(minHeight = if (fillEditorHeight) editorHeight else 0, maxHeight = editorHeight),
            )
        placeables[editor] = editorPlaceable
        usedHeight += editorGap + editorPlaceable.height
        if (editorPlaceable.height > 0) visibleCount++
        if (suggestions >= 0) {
            val gap = if (visibleCount > 0) spacing else 0
            val available = (constraints.maxHeight - usedHeight - gap).coerceAtLeast(0)
            val placeable = measurables[suggestions].measure(constraints.copy(minHeight = 0, maxHeight = available))
            placeables[suggestions] = placeable
            if (placeable.height > 0) usedHeight += gap + placeable.height
        }
        val children = placeables.filterNotNull()
        layout(
            constraints.constrainWidth(children.maxOfOrNull { it.width } ?: 0),
            constraints.constrainHeight(usedHeight),
        ) {
            placeComposerChildren(children, spacing)
        }
    }
}

/** Places only visible slots in their original order, without gaps for an empty suggestion viewport. */
private fun Placeable.PlacementScope.placeComposerChildren(
    children: List<Placeable>,
    spacing: Int,
) {
    var y = 0
    var placed = false
    children.forEach { child ->
        if (child.height > 0) {
            if (placed) y += spacing
            child.placeRelative(0, y)
            y += child.height
            placed = true
        }
    }
}
