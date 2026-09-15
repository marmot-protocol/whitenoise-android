package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Reserves pinned actions above global feedback without hiding its error or Copy action.
 * Attach after nav/IME padding so the measured action height excludes system insets, including consumed ancestors.
 */
@Composable
internal fun Modifier.reserveSnackbarSpace(): Modifier {
    val inset = LocalSnackbarBottomInset.current
    val density = LocalDensity.current
    var actionHeight by remember { mutableStateOf(0.dp) }
    DisposableEffect(actionHeight, inset) {
        val previous = inset.value
        if (actionHeight > previous) inset.value = actionHeight
        onDispose {
            if (actionHeight > previous && inset.value == actionHeight) inset.value = previous
        }
    }
    return onSizeChanged { size -> actionHeight = with(density) { size.height.toDp() } }
}
