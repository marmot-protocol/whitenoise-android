package dev.ipf.whitenoise.android.ui.conversation.composer

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Returns the smallest scroll offset that exposes the current selection. A
 * selection that fits is kept wholly visible; a selection taller than the
 * viewport follows its active edge instead of jumping to the end of the
 * draft. Returning the current offset is intentional when no motion is
 * needed, which keeps bulk IME commits from fighting the timeline re-anchor.
 */
internal fun composerCaretScrollTarget(
    currentScroll: Int,
    viewportHeight: Int,
    maxScroll: Int,
    selection: ComposerSelectionLayout,
): Int {
    if (viewportHeight <= 0 || maxScroll <= 0) return currentScroll.coerceIn(0, maxScroll.coerceAtLeast(0))

    val viewportTop = currentScroll.toFloat()
    val viewportBottom = viewportTop + viewportHeight
    val selectionHeight = (selection.bottom - selection.top).coerceAtLeast(0f)
    val desired =
        if (selectionHeight <= viewportHeight) {
            when {
                selection.top < viewportTop -> floor(selection.top).toInt()
                selection.bottom > viewportBottom -> ceil(selection.bottom - viewportHeight).toInt()
                else -> currentScroll
            }
        } else {
            when {
                selection.activeCaretTop < viewportTop -> floor(selection.activeCaretTop).toInt()
                selection.activeCaretBottom > viewportBottom ->
                    ceil(selection.activeCaretBottom - viewportHeight).toInt()
                else -> currentScroll
            }
        }
    return desired.coerceIn(0, maxScroll)
}
