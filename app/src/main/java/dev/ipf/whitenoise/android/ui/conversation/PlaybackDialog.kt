package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.state.observePlaybackTransportVisible

private val LocalPlaybackSourceAllowed = staticCompositionLocalOf { true }
private val LocalPlaybackDismissals = staticCompositionLocalOf<List<() -> Unit>> { emptyList() }

/**
 * Full-screen destinations retain their own secure window and Back behavior while sharing transport controls.
 * Source navigation closes the current modal stack through its normal dismissal callbacks before routing.
 * Non-shell previews and fixtures keep their original layout when no host is installed.
 */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    onSourceDismiss: (() -> Unit)? = onDismissRequest,
    content: @Composable () -> Unit,
) {
    val host = LocalShellPlaybackHost.current
    val sourceAllowed = LocalPlaybackSourceAllowed.current && onSourceDismiss != null
    val parentDismissals = LocalPlaybackDismissals.current
    val latestDismissal by rememberUpdatedState(onSourceDismiss)
    val dismissCurrent =
        remember {
            {
                latestDismissal?.invoke()
                Unit
            }
        }
    val hasSourceDismissal = onSourceDismiss != null
    // Callback refreshes must not invalidate every native selectable child in the modal hierarchy.
    val dismissals =
        remember(parentDismissals, hasSourceDismissal) {
            if (hasSourceDismissal) parentDismissals + dismissCurrent else parentDismissals
        }
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        if (host == null) {
            content()
        } else {
            CompositionLocalProvider(
                LocalPlaybackDismissals provides dismissals,
                LocalPlaybackSourceAllowed provides sourceAllowed,
            ) {
                val visible = host.appState.observePlaybackTransportVisible() && !host.appState.appLockScreenVisible
                Column(Modifier.fillMaxSize()) {
                    if (visible) {
                        Box(if (properties.decorFitsSystemWindows) Modifier else Modifier.statusBarsPadding()) {
                            PlaybackTransportBar(
                                host.appState,
                                onBodyClick =
                                    onSourceDismiss?.takeIf { sourceAllowed }?.let {
                                        {
                                            host.requestOpenSource {
                                                dismissals.asReversed().forEach { dismiss -> dismiss() }
                                            }
                                        }
                                    },
                            )
                        }
                    }
                    val insets = if (visible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
                    Box(Modifier.weight(1f).then(insets)) { content() }
                }
            }
        }
    }
}
