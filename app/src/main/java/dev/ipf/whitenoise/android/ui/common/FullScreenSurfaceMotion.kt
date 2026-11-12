package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion

/** Fullscreen windows without a valid launch control fade; they never invent a popup-sized origin. */
@Composable
internal fun fullScreenSurfaceMotion(
    surface: PopupSurfaceMotion,
    fade: () -> Boolean = { true },
): Modifier {
    DialogWindowMotion(surface.presence, dimAmount = 0f)
    val acceptsInput = surface.acceptsInput && surface.presence.value >= 1f
    return Modifier
        .graphicsLayer { alpha = if (fade()) surface.presence.value else 1f }
        .then(if (acceptsInput) Modifier else Modifier.clearAndSetSemantics {})
        .onPreviewKeyEvent { !acceptsInput && it.key != Key.Back }
        .pointerInput(surface) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!surface.acceptsInput || surface.presence.value < 1f) event.changes.forEach { it.consume() }
                }
            }
        }
}
