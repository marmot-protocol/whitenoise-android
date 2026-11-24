package dev.ipf.whitenoise.android.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Retain just a control rectangle, never pixels or a long-lived navigation registry. */
@Composable
internal fun rememberMeasuredControlClick(onClick: (Rect?) -> Unit): Pair<Modifier, () -> Unit> {
    val view = LocalView.current
    val bounds = remember { arrayOf(Rect.Zero) }
    return Modifier.onGloballyPositioned { bounds[0] = it.motionBoundsOnScreen(view) } to {
        onClick(bounds[0].takeUnless { it.isEmpty })
    }
}

/** Controls inside popup windows and their destination host share screen coordinates. */
internal fun LayoutCoordinates.motionBoundsOnScreen(view: View): Rect {
    val screen = IntArray(2)
    val window = IntArray(2)
    view.getLocationOnScreen(screen)
    view.getLocationInWindow(window)
    return boundsInWindow().translate(Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()))
}
