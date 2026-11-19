package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** All passive overlay surfaces retain the initiating hold instead of dismissing on its release. */
internal fun Modifier.focusedDismissOnTap(
    initiatingHoldActive: () -> Boolean,
    onDismiss: () -> Unit,
    awaitRelease: Boolean = false,
): Modifier =
    pointerInput(Unit) {
        // The preview opens underneath the held pointer; discard that gesture before detecting taps.
        if (awaitRelease) {
            awaitPointerEventScope {
                while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
            }
        }
        detectTapGestures { if (!initiatingHoldActive()) onDismiss() }
    }
