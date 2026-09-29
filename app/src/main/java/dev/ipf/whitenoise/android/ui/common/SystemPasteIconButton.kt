@file:Suppress("FunctionNaming") // Composable functions use framework naming.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalTextToolbar

/**
 * Opens the platform Paste action. Clipboard access in [onPaste] occurs only after the user
 * selects that action, so systems with Secure Paste can grant access to this clipboard item.
 */
@Composable
internal fun SystemPasteIconButton(
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val toolbar = LocalTextToolbar.current
    val latestOnPaste by rememberUpdatedState(onPaste)
    var bounds by remember { mutableStateOf(Rect.Zero) }

    IconButton(
        onClick = {
            toolbar.showMenu(
                rect = bounds,
                onPasteRequested = { latestOnPaste() },
            )
        },
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        enabled = enabled,
        content = content,
    )
}
