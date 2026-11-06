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
    val pasteAccepted = rememberSystemPasteGuard(enabled)
    var bounds by remember { mutableStateOf(Rect.Zero) }

    IconButton(
        onClick = {
            if (!latestOnPaste()) {
                toolbar.showMenu(
                    rect = bounds,
                    onPasteRequested = { if (pasteAccepted()) latestOnPaste() },
                )
            }
        },
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        enabled = enabled,
        content = content,
    )
}

/**
 * Owns a system Paste menu opened by its caller: hides the menu when the caller leaves composition and
 * returns whether a late Paste from that menu may still run, i.e. the caller is present and [enabled].
 */
@Composable
internal fun rememberSystemPasteGuard(enabled: Boolean): () -> Boolean {
    val toolbar = LocalTextToolbar.current
    val latestEnabled by rememberUpdatedState(enabled)
    var active by remember(toolbar) { mutableStateOf(true) }
    DisposableEffect(toolbar) {
        active = true
        onDispose {
            active = false
            toolbar.hide()
        }
    }
    return { active && latestEnabled }
}
