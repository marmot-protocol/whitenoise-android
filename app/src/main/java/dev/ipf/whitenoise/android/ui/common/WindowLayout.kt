package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Window width bands at Material's window size class breakpoints (600dp and 840dp). */
internal enum class WindowWidthClass { Compact, Medium, Expanded }

/** Window height bands at Material's window size class breakpoints (480dp and 900dp). */
internal enum class WindowHeightClass { Compact, Medium, Expanded }

/**
 * The shape of the activity window, read once at the root and shared through
 * [LocalWindowLayout].
 *
 * Layout state that depends on how much room the window has should key on this
 * rather than on `Configuration.orientation`. Opening a book-style foldable
 * changes the window from a narrow portrait strip to a near-square pane, often
 * without changing orientation, and a split-screen or freeform window is
 * smaller than the display it sits on.
 */
@Immutable
internal data class WindowLayout(
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
    val isLandscape: Boolean,
) {
    companion object {
        /** A portrait phone; the value seen by compositions that are not under [ProvideWindowLayout]. */
        val CompactPortrait =
            WindowLayout(
                widthClass = WindowWidthClass.Compact,
                heightClass = WindowHeightClass.Medium,
                isLandscape = false,
            )

        fun fromSize(size: DpSize): WindowLayout =
            WindowLayout(
                widthClass =
                    when {
                        size.width < MediumWidthBreakpoint -> WindowWidthClass.Compact
                        size.width < ExpandedWidthBreakpoint -> WindowWidthClass.Medium
                        else -> WindowWidthClass.Expanded
                    },
                heightClass =
                    when {
                        size.height < MediumHeightBreakpoint -> WindowHeightClass.Compact
                        size.height < ExpandedHeightBreakpoint -> WindowHeightClass.Medium
                        else -> WindowHeightClass.Expanded
                    },
                isLandscape = size.width > size.height,
            )
    }
}

private val MediumWidthBreakpoint = 600.dp
private val ExpandedWidthBreakpoint = 840.dp
private val MediumHeightBreakpoint = 480.dp
private val ExpandedHeightBreakpoint = 900.dp

internal val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.CompactPortrait }

/**
 * Provides the activity window's [WindowLayout] to [content].
 *
 * Call this once, at the activity root. Dialogs and popups have their own
 * window, so reading the window size inside one would describe the dialog;
 * composition locals carry the activity's value into them instead.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ProvideWindowLayout(content: @Composable () -> Unit) {
    val windowSize = LocalWindowInfo.current.containerDpSize
    val layout = remember(windowSize) { WindowLayout.fromSize(windowSize) }
    CompositionLocalProvider(LocalWindowLayout provides layout, content = content)
}
