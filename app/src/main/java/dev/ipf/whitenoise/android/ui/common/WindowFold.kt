package dev.ipf.whitenoise.android.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
 * A vertical fold or hinge that splits the activity window into a left and a right side, such as a
 * book-style foldable held half open or a dual-screen device.
 *
 * [left] and [right] are the hinge's edges measured from the window's left edge, whatever the
 * layout direction. A fold without a physical gap has `left == right`.
 *
 * It is kept apart from [WindowLayout]: half-closing a fold does not resize the window, so it must
 * not reset state keyed on the window's shape, such as the composer's remembered pane heights.
 */
@Immutable
internal data class VerticalWindowFold(
    val left: Dp,
    val right: Dp,
)

/**
 * The separating vertical fold in the activity window, or null when there is none, the device is
 * flat, or either side would be too narrow to hold a screen. Provided by [ProvideWindowLayout].
 */
internal val LocalVerticalWindowFold = staticCompositionLocalOf<VerticalWindowFold?> { null }

/** Narrowest side of a fold that still gets its own pane. */
internal val MinWindowFoldSideWidth = 300.dp

/**
 * Picks the fold that content should not straddle.
 *
 * Only a [FoldingFeature.isSeparating] fold counts: a half-open fold, or a hinge that hides content.
 * A foldable opened flat reads as one screen and keeps the regular layout.
 */
internal fun separatingVerticalFold(
    features: List<FoldingFeature>,
    windowWidthPx: Int,
    density: Density,
): VerticalWindowFold? {
    val fold =
        features.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
            ?: return null
    val bounds = fold.bounds
    return with(density) {
        val left = bounds.left.toDp()
        val right = bounds.right.toDp()
        val window = windowWidthPx.toDp()
        if (left < MinWindowFoldSideWidth || window - right < MinWindowFoldSideWidth) {
            null
        } else {
            VerticalWindowFold(left = left, right = right)
        }
    }
}

/** Follows the activity window's folds; null outside an activity or before the first report. */
@Composable
internal fun rememberVerticalWindowFold(): VerticalWindowFold? {
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val fold by
        produceState<VerticalWindowFold?>(initialValue = null, activity, density, windowWidthPx) {
            if (activity == null) return@produceState
            WindowInfoTracker
                .getOrCreate(activity)
                .windowLayoutInfo(activity)
                // A device without window extensions keeps the regular layout.
                .catch { value = null }
                .collect { info ->
                    value =
                        separatingVerticalFold(
                            features = info.displayFeatures.filterIsInstance<FoldingFeature>(),
                            windowWidthPx = windowWidthPx,
                            density = density,
                        )
                }
        }
    return fold
}
