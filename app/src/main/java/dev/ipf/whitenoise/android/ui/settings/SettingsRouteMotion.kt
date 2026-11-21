package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/** Present one live destination. A measured, outlined route may supply its own contour transition. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun <T> SettingsRouteMotion(
    target: T,
    depth: Int,
    ownerKey: Any?,
    suppressMotion: Boolean = false,
    contentVisible: Boolean = true,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    cardMorph: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    if (!contentVisible) return
    val parent = LocalSettingsCardMorph.current
    val morph =
        if (cardMorph) {
            remember(ownerKey, parent) {
                SettingsCardMorph(fullScreen = true, parent = parent)
            }
        } else {
            null
        }
    val motion = animationsEnabled && !suppressMotion
    val lastDepth = remember(ownerKey) { mutableIntStateOf(depth) }
    val previousDepth = remember(ownerKey, target) { lastDepth.intValue }
    val forward = settingsRouteDirection(previousDepth, depth) > 0
    SideEffect { lastDepth.intValue = depth }
    val (_, routeMotion) =
        rememberSettingsCardMorphHost(
            morph,
            target,
            ownerKey,
            motion,
            forward,
        )
    Box(Modifier.fillMaxSize().then(routeMotion)) {
        CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
            content(target)
        }
    }
}
