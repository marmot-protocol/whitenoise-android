package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.DetailPaneMinWidth
import dev.ipf.whitenoise.android.ui.common.ListPaneMinWidth
import dev.ipf.whitenoise.android.ui.common.WindowFold
import dev.ipf.whitenoise.android.ui.common.WindowFolds
import dev.ipf.whitenoise.android.ui.common.WindowLayout

/** Widest the chat list grows on regular-height windows such as tablets; the rest goes to the conversation. */
internal val TwoPaneListWidth = 360.dp

/** Share of the width after the divider that the chat list takes on regular-height windows. */
internal const val REGULAR_WINDOW_LIST_FRACTION = 0.4f

/**
 * Share of the width after the divider that the chat list takes on short, wide windows such as a
 * phone held horizontally (#3866), up to [ShortWindowListMaxWidth].
 */
internal const val SHORT_WINDOW_LIST_FRACTION = 0.5f

/**
 * Widest the chat list grows on short windows. Phones held horizontally stay below it and keep the
 * even split; wider short windows, such as a tablet in split screen or a desktop window, give the
 * extra width to the conversation instead of the list.
 */
internal val ShortWindowListMaxWidth = 480.dp

/** Widest list a flat fold's crease may set; beyond it the proportional split wins. */
internal val FlatFoldListMaxWidth = 480.dp

/**
 * Whether the chat list stays beside the conversation: whenever both fit at their minimum widths,
 * and on a foldable held half open like a book, where each side of the fold holds one pane.
 */
internal fun listBesideConversation(
    layout: WindowLayout,
    folds: WindowFolds,
): Boolean = layout.fitsTwoPanes || folds.upright.any { it.separating }

/** The chat list's width and the conversation's span, in pixels from the start edge. */
internal data class ChatPaneSplit(
    val listWidth: Int,
    val conversationStart: Int,
    val conversationEnd: Int,
)

/**
 * Splits [width] pixels between the chat list and the conversation.
 *
 * A half-open fold always sets the split, so neither pane is drawn across the hinge. A flat fold
 * sets it when its crease leaves both panes a good width. Otherwise the list takes a share of the
 * width, never narrower than [ListPaneMinWidth] and never leaving the conversation narrower than
 * [DetailPaneMinWidth].
 *
 * [folds] are measured from the window's left edge, so they apply only while the frame spans the
 * whole window; pass none otherwise.
 */
internal fun Density.chatPaneSplit(
    width: Int,
    dividerWidth: Int,
    shortWindow: Boolean,
    folds: List<WindowFold>,
    layoutDirection: LayoutDirection,
): ChatPaneSplit {
    foldSplit(width, dividerWidth, folds, layoutDirection)?.let { return it }
    val space = (width - dividerWidth).coerceAtLeast(0)
    // Truncating gives an odd leftover pixel to the conversation.
    val preferred =
        if (shortWindow) {
            minOf((space * SHORT_WINDOW_LIST_FRACTION).toInt(), ShortWindowListMaxWidth.roundToPx())
        } else {
            (space * REGULAR_WINDOW_LIST_FRACTION).toInt().coerceAtMost(TwoPaneListWidth.roundToPx())
        }
    val minList = ListPaneMinWidth.roundToPx()
    val maxList = maxOf(minList, space - DetailPaneMinWidth.roundToPx())
    val listWidth = preferred.coerceIn(minList, maxList).coerceAtMost(space)
    return ChatPaneSplit(listWidth, conversationStart = listWidth + dividerWidth, conversationEnd = width)
}

private fun Density.foldSplit(
    width: Int,
    dividerWidth: Int,
    folds: List<WindowFold>,
    layoutDirection: LayoutDirection,
): ChatPaneSplit? {
    // Measured from the start edge: in right-to-left layouts the list sits right of the fold.
    val spans =
        folds
            .map { fold ->
                val left = fold.start.roundToPx()
                val right = fold.end.roundToPx()
                if (layoutDirection == LayoutDirection.Ltr) {
                    FoldSpan(left, right, fold.separating)
                } else {
                    FoldSpan(width - right, width - left, fold.separating)
                }
            }.sortedBy { it.start }
    val conversationStartAfter = { span: FoldSpan -> maxOf(span.end, span.start + dividerWidth) }
    val creaseFits = { span: FoldSpan ->
        span.start in ListPaneMinWidth.roundToPx()..FlatFoldListMaxWidth.roundToPx() &&
            width - conversationStartAfter(span) >= DetailPaneMinWidth.roundToPx()
    }
    // A half-open hinge beats a flat crease; with several, the narrowest list leaves the most conversation.
    val chosen =
        spans.firstOrNull { it.separating }
            ?: spans.firstOrNull { !it.separating && creaseFits(it) }
            ?: return null
    val listWidth = chosen.start.coerceIn(0, width)
    // A hinge with a physical gap leaves the gap empty; a seamless fold keeps the divider on it.
    val conversationStart = conversationStartAfter(chosen).coerceAtMost(width)
    // A second half-open hinge, as on a tri-fold folded into a Z, ends the conversation before it.
    val conversationEnd =
        spans.firstOrNull { it.separating && it.start > conversationStart }?.start ?: width
    return ChatPaneSplit(listWidth, conversationStart, conversationEnd)
}

private data class FoldSpan(
    val start: Int,
    val end: Int,
    val separating: Boolean,
)
