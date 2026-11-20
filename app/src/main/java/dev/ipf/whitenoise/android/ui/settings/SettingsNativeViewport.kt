package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Measures a real scroll boundary; callers provide its token to the native containers inside. */
@Composable
internal fun rememberViewport(page: SettingsPageMorph? = LocalSettingsCardMorph.current?.page): Pair<Any, Modifier> {
    val token = remember(page) { Any() }
    val view = LocalView.current
    DisposableEffect(page, token) { onDispose { page?.removeViewport(token) } }
    return token to Modifier.onGloballyPositioned { page?.reportViewport(token, it.settingsMorphBounds(view)) }
}

/** Associates existing native containers with their actual scroll viewport without adding a surface. */
@Composable
@Suppress("FunctionNaming")
internal fun SettingsNativeViewport(content: @Composable (Modifier) -> Unit) {
    val (token, modifier) = rememberViewport()
    CompositionLocalProvider(LocalSettingsNativeViewport provides token) { content(modifier) }
}
