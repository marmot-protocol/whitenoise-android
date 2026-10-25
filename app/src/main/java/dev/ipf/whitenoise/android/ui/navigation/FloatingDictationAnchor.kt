@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Only visible layout obstacles, including both sides of an animated route transition. */
internal class FloatingDictationAnchor {
    private val bounds = mutableStateMapOf<Any, Rect>()
    val topInWindow: Float? get() = bounds.values.minOfOrNull { it.top }

    fun report(
        token: Any,
        visibleBounds: Rect,
    ) {
        if (visibleBounds.width > 0f && visibleBounds.height > 0f) {
            bounds[token] = visibleBounds
        } else {
            remove(token)
        }
    }

    fun remove(token: Any) {
        bounds.remove(token)
    }
}

internal val LocalFloatingDictationAnchor = staticCompositionLocalOf<FloatingDictationAnchor?> { null }

/** Attach to the actual bottom chrome, including its IME layout, rather than guessing its height. */
@Composable
internal fun Modifier.floatingDictationAnchor(): Modifier {
    val anchor = LocalFloatingDictationAnchor.current
    val token = remember(anchor) { Any() }
    DisposableEffect(anchor, token) { onDispose { anchor?.remove(token) } }
    // The shell places content before its overlay. Publish during that same placement pass,
    // before the capsule reads the obstacle, including the first navigation frame.
    return onPlaced { coordinates -> anchor?.report(token, coordinates.boundsInWindow()) }
}

/** Overlay placement never reserves a full-width strip or changes the destination's IME handling. */
@Composable
internal fun FloatingDictationLayout(
    anchor: FloatingDictationAnchor,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val fallbackInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    var rootTop by remember { mutableStateOf(0f) }
    Layout(
        content = content,
        modifier = Modifier.fillMaxSize().onPlaced { rootTop = it.positionInWindow().y },
    ) { measurables, constraints ->
        val bubble = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val bottom = anchor.topInWindow?.minus(rootTop)?.roundToInt() ?: (constraints.maxHeight - fallbackInset)
            val safeTop = (statusBarTop - rootTop).roundToInt().coerceAtLeast(0)
            val availableGap = (bottom - bubble.height - safeTop).coerceIn(0, 12.dp.roundToPx())
            bubble.place(
                x = (constraints.maxWidth - bubble.width - 16.dp.roundToPx()).coerceAtLeast(0),
                y = (bottom - bubble.height - availableGap).coerceAtLeast(safeTop),
            )
        }
    }
}
