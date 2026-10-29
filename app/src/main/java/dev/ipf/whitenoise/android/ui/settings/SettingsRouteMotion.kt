package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

internal const val SETTINGS_ROUTE_MOTION_MILLIS = 200

/** Navigation hierarchy supplies direction; peer destinations use the forward treatment. */
internal fun settingsRouteDirection(
    previousDepth: Int,
    nextDepth: Int,
): Int = if (nextDepth < previousDepth) -1 else 1

/** Derives nested depth from the same parent relation used by Back. */
internal fun settingsDetailDepth(detail: SettingsDetail?): Int {
    if (detail == null) return 0
    return 1 + settingsDetailDepth(settingsDetailParent(detail))
}

private class SettingsMotionHistory<T>(
    var target: T,
    var depth: Int,
)

/**
 * Slide the opaque destination's draw layer. The old route is disposed immediately: it cannot keep
 * Back handlers, editable state, focus, or protected pixels alive during an exit animation. Keeping
 * the content call outside a route key preserves the existing screen/state ownership semantics.
 * First composition (including a direct privacy-sensitive route) is settled, never a delayed reveal.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun <T> SettingsRouteMotion(
    target: T,
    depth: Int,
    ownerKey: Any?,
    suppressMotion: Boolean = false,
    contentVisible: Boolean = true,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    content: @Composable (T) -> Unit,
) {
    if (!contentVisible) return
    Box(
        Modifier.fillMaxSize().then(
            rememberRouteMotionModifier(target, depth, ownerKey, suppressMotion, animationsEnabled),
        ),
    ) {
        content(target)
    }
}

/** A single destination draw layer for route hosts with existing early-return rendering. */
@Composable
internal fun <T> rememberRouteMotionModifier(
    target: T,
    depth: Int,
    ownerKey: Any?,
    suppressMotion: Boolean = false,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
): Modifier {
    val history = remember(ownerKey) { SettingsMotionHistory(target, depth) }
    val layoutDirection = LocalLayoutDirection.current
    val logicalDirection =
        remember(ownerKey, target) { settingsRouteDirection(history.depth, depth) }
    val direction = if (layoutDirection == LayoutDirection.Rtl) -logicalDirection else logicalDirection
    val motion = animationsEnabled && !suppressMotion
    val progress = remember(ownerKey, target, motion) { Animatable(if (motion && history.target != target) 0f else 1f) }
    SideEffect {
        history.target = target
        history.depth = depth
    }
    LaunchedEffect(progress) {
        if (motion) {
            progress.animateTo(1f, tween(SETTINGS_ROUTE_MOTION_MILLIS, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    val travel = with(LocalDensity.current) { 48.dp.toPx() }
    // Draw the themed backplate outside the translated child layer. Moving a whole opaque page
    // otherwise exposes the Activity window behind its vacated edge, including in AMOLED mode.
    val backplate = MaterialTheme.colorScheme.background
    return Modifier
        .drawBehind {
            if (motion && progress.value < 1f) drawRect(backplate)
        }.graphicsLayer {
            val remaining = if (motion) 1f - progress.value else 0f
            translationX = direction * travel * remaining
        }
}
