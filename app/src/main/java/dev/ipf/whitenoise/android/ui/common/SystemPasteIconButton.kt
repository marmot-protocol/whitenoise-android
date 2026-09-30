@file:Suppress("FunctionNaming") // Composable functions use framework naming.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * Attempts Paste only after an explicit tap. [onPaste] returns true when the clipboard was
 * readable, including when field validation rejected it. If access was denied, opens the
 * platform Paste action so Secure Paste can grant access without changing the user's policy.
 */
@Composable
internal fun SystemPasteIconButton(
    onPaste: () -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val toolbar = LocalTextToolbar.current
    val latestOnPaste by rememberUpdatedState(onPaste)
    val latestEnabled by rememberUpdatedState(enabled)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var active by remember(toolbar) { mutableStateOf(true) }

    DisposableEffect(toolbar) {
        active = true
        onDispose {
            active = false
            toolbar.hide()
        }
    }

    IconButton(
        onClick = {
            if (!latestOnPaste()) {
                toolbar.showMenu(
                    rect = bounds,
                    onPasteRequested = { if (active && latestEnabled) latestOnPaste() },
                )
            }
        },
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        enabled = enabled,
        content = content,
    )
}
