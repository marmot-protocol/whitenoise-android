package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent

/** A composer reserves geometry only. The shell owns the single real dictation action set. */
internal val LocalShellDictationControls = staticCompositionLocalOf<FloatingDictationAnchor?> { null }

/** Keeps the original composer's layout without cloning actions, semantics, audio or private text. */
@Suppress("FunctionNaming")
@Composable
internal fun DictationComposerControlSlot(
    width: Dp,
    colors: AccountActionColors,
) {
    if (!LocalControlHandoffEndpointCurrent.current) return
    val anchor = LocalShellDictationControls.current
    val token = remember(anchor) { Any() }
    DisposableEffect(anchor, token) { onDispose { anchor?.removeComposerControls(token) } }
    Box(
        Modifier
            .width(width)
            .height(48.dp)
            .onPlaced { anchor?.reportComposerControls(token, it.boundsInWindow(), colors) },
    )
}
