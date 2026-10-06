package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Supplies the same source-navigation owner to separate full-screen windows without global UI state. */
internal class ShellPlaybackHost(
    val appState: WhiteNoiseAppState,
    val openSource: () -> Unit,
)

internal val LocalShellPlaybackHost = staticCompositionLocalOf<ShellPlaybackHost?> { null }
