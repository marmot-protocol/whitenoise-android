package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.state.observePlaybackTransportVisible
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutBlocker

/**
 * In-place Chats destinations retain their transport above content without adding another dialog or root inset.
 * They own input in place of Chats, so its keyboard shortcuts wait until they close.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackFlowContent(
    enabled: Boolean,
    onSourceLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalShellPlaybackHost.current
    val visible =
        enabled &&
            host != null &&
            host.appState.observePlaybackTransportVisible() &&
            !host.appState.appLock.screenVisible
    HardwareShortcutBlocker(chatsOnly = true)
    Column(Modifier.fillMaxSize()) {
        if (host != null && visible) {
            Box(Modifier.statusBarsPadding()) {
                PlaybackTransportBar(host.appState, onBodyClick = { host.requestOpenSource(onSourceLeave) })
            }
        }
        val insets = if (visible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).then(insets)) { content() }
    }
}
