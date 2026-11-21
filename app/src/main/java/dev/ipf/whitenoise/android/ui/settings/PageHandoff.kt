package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.AppPageTravel

/** One committed destination owns its layer reports, including the interval before its first draw. */
private class PresentedPage<T>(
    val target: T,
    var translationPx: Float = 0f,
    var inFlight: Boolean = false,
)

private class PagePresentation<T>(
    initial: T,
) {
    var current = PresentedPage(initial)
}

/** Ordinary route navigation moves the live page at native size. The surface under its edge stays opaque. */
@Composable
internal fun <T> rememberPageHandoff(
    target: T,
    ownerKey: Any?,
    forward: Boolean,
    enabled: Boolean,
    contourActive: Boolean = false,
): Modifier {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val travelPx = with(LocalDensity.current) { direction * AppPageTravel.toPx() }
    val presentation = remember(ownerKey) { PagePresentation(target) }
    val offset =
        remember(presentation, target, enabled) {
            val previous = presentation.current
            val entry =
                when {
                    !enabled || previous.target == target -> 0f
                    previous.inFlight -> previous.translationPx / travelPx
                    forward -> 1f
                    else -> -1f
                }
            Animatable(entry)
        }
    val presented =
        remember(offset, contourActive) {
            val moving = enabled && !contourActive && offset.value != 0f
            PresentedPage(target, if (moving) presentation.current.translationPx else 0f, inFlight = moving)
        }
    SideEffect { presentation.current = presented }
    // Reserve the starting offset while an outlined destination is being measured. Rejection
    // can then admit this very navigation without another target change or a second clock.
    if (!enabled || contourActive) return Modifier
    LaunchedEffect(offset) {
        val millis = if (forward) APP_MOTION_OPEN_MILLIS else APP_MOTION_CLOSE_MILLIS
        val easing = if (forward) AppMotionOpenEasing else AppMotionCloseEasing
        offset.animateTo(0f, tween(millis, easing = easing))
    }
    val moving by remember(offset) { derivedStateOf { offset.value != 0f } }
    val backplate = MaterialTheme.colorScheme.background
    return Modifier
        .clipToBounds()
        .drawBehind { if (moving) drawRect(backplate) }
        .blockMovingPageInput(offset) { moving }
        .graphicsLayer {
            translationX = travelPx * offset.value
            presented.translationPx = translationX
            presented.inFlight = offset.value != 0f
        }
}

private fun Modifier.blockMovingPageInput(
    animationKey: Any,
    moving: () -> Boolean,
): Modifier =
    semantics { if (moving()) hideFromAccessibility() }
        .onPreviewKeyEvent { moving() && it.key != Key.Back && it.key != Key.Escape }
        .pointerInput(animationKey) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (moving()) event.changes.forEach { it.consume() }
                }
            }
        }
