package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay

private const val MENU_KEYBOARD_HIDE_TIMEOUT_MILLIS = 1_500L

/** A refused keyboard hide cannot strand an invisible, inaccessible menu. */
@Composable
internal fun focusedMessageImeDismissEffect(
    imeVisible: Boolean,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(MENU_KEYBOARD_HIDE_TIMEOUT_MILLIS)
            currentOnDismiss()
        }
    }
}

/** Hidden lifted content exposes neither accessibility actions nor pointer targets. */
internal fun Modifier.focusedStackInputGuard(visible: Boolean): Modifier =
    then(
        if (!visible) {
            Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        } else {
            Modifier
        },
    )

internal fun Modifier.focusedStackAlpha(visible: Boolean): Modifier = graphicsLayer { alpha = if (visible) 1f else 0f }
