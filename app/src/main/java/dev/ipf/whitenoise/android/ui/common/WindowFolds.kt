package dev.ipf.whitenoise.android.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.catch

/**
 * One fold or hinge in the activity window, measured along the axis it divides: from the window's
 * left edge for an upright fold, whatever the layout direction, and from its top edge for a
 * horizontal one. A fold without a physical gap has `start == end`.
 *
 * [separating] is true while the fold is half open, or for a hinge that hides content. A flat fold
 * still marks the crease, but content may cross it.
 */
@Immutable
internal data class WindowFold(
    val start: Dp,
    val end: Dp,
    val separating: Boolean,
)

/**
 * The folds that leave usable room on both sides, read from the activity window.
 *
 * Book-style foldables and dual-screen devices report an upright fold; a tri-fold can report two,
 * of which at most one half-open hinge is kept.
 * A flip phone held half open like a laptop, or a book-style foldable turned on its side, reports a
 * separating horizontal fold: the tabletop posture. Flip phones opened flat and phones without a
 * fold report none.
 *
 * Kept apart from [WindowLayout]: folding or unfolding without resizing the window must not reset
 * state keyed on the window's shape, such as the composer's remembered pane heights.
 */
@Immutable
internal data class WindowFolds(
    val upright: List<WindowFold> = emptyList(),
    val tabletop: WindowFold? = null,
) {
    companion object {
        val None = WindowFolds()
    }
}

/** The activity window's folds. Provided by [ProvideWindowLayout]. */
internal val LocalWindowFolds = staticCompositionLocalOf { WindowFolds.None }

/** Narrowest side of an upright fold that still gets its own pane. */
internal val MinWindowFoldSideWidth = 300.dp

/** Shortest half of a tabletop fold that still holds the conversation or its composer. */
internal val MinTabletopHalfHeight = 200.dp

/** Keeps the folds that leave usable room on both sides; everything else keeps the regular layout. */
internal fun windowFolds(
    features: List<FoldingFeature>,
    windowWidthPx: Int,
    windowHeightPx: Int,
    density: Density,
): WindowFolds =
    with(density) {
        val width = windowWidthPx.toDp()
        val height = windowHeightPx.toDp()
        val vertical = features.filter { it.orientation == FoldingFeature.Orientation.VERTICAL }
        // Two half-open upright hinges, as on a tri-fold folded into a Z, leave no single split that
        // keeps both panes off every hinge, so the regular layout applies.
        val halfOpen = vertical.count { it.isSeparating }
        val upright =
            vertical
                .filterNot { it.isSeparating && halfOpen > 1 }
                .map { WindowFold(it.bounds.left.toDp(), it.bounds.right.toDp(), it.isSeparating) }
                .filter { it.start >= MinWindowFoldSideWidth && width - it.end >= MinWindowFoldSideWidth }
                .sortedBy { it.start }
        val tabletop =
            features
                .filter { it.isSeparating && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                .map { WindowFold(it.bounds.top.toDp(), it.bounds.bottom.toDp(), separating = true) }
                .firstOrNull { it.start >= MinTabletopHalfHeight && height - it.end >= MinTabletopHalfHeight }
        if (upright.isEmpty() && tabletop == null) WindowFolds.None else WindowFolds(upright, tabletop)
    }

/** Follows the activity window's folds; [WindowFolds.None] outside an activity or before the first report. */
@Composable
internal fun rememberWindowFolds(): WindowFolds {
    val activity = LocalActivity.current ?: return WindowFolds.None
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    // Keep the platform observation in pixels so resizing can re-evaluate it immediately.
    val features by key(activity) {
        produceState(emptyList<FoldingFeature>()) {
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                // A device without window extensions keeps the regular layout.
                .catch { value = emptyList() }
                .collect { info -> value = info.displayFeatures.filterIsInstance<FoldingFeature>() }
        }
    }
    return remember(features, windowSize, density) {
        windowFolds(features, windowSize.width, windowSize.height, density)
    }
}
