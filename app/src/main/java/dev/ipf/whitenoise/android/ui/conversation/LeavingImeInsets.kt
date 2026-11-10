package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

/**
 * A route that replaces the focused composer inherits a keyboard that is already closing. Measured
 * against that shrinking inset, the incoming page would stretch down from above the keyboard while
 * it slides in, so it lays out for the closed keyboard until the inset settles. A keyboard the page
 * opens itself (the inset growing again) is honoured as usual.
 */
@Composable
internal fun rememberLeavingImeModifier(routeKey: Any?): Modifier {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    var leaving by remember(routeKey) { mutableStateOf(ime.getBottom(density) > 0) }
    if (leaving) {
        LaunchedEffect(routeKey) {
            var previous = Int.MAX_VALUE
            snapshotFlow { ime.getBottom(density) }.first { bottom ->
                imeLeavingSettled(previous, bottom).also { previous = bottom }
            }
            leaving = false
        }
    }
    return if (leaving) Modifier.consumeWindowInsets(WindowInsets.ime) else Modifier
}

/** The inherited keyboard is gone once it reaches zero, or a new one is opening over the page. */
internal fun imeLeavingSettled(
    previousBottom: Int,
    bottom: Int,
): Boolean = bottom == 0 || bottom > previousBottom
