package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.audio.matchesPlaybackSession
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.currentPlaybackConversationDestination

/** Supplies one source-navigation owner and destination leave guards to every shell window. */
internal class ShellPlaybackHost(
    val appState: WhiteNoiseAppState,
    private val openSource: () -> Unit,
) {
    private val leaveGuards = linkedMapOf<Any, (() -> Unit) -> Unit>()

    /** Revalidates playback ownership after any asynchronous dirty-draft confirmation, then closes modal windows. */
    fun requestOpenSource(beforeNavigate: () -> Unit = {}) {
        val destination = appState.currentPlaybackConversationDestination() ?: return
        val navigate = {
            val current = destination.matchesPlaybackSession(appState.currentPlaybackConversationDestination())
            if (current && !appState.appLockScreenVisible) {
                beforeNavigate()
                openSource()
            }
        }
        leaveGuards.values.fold(navigate) { next, guard -> { guard(next) } }.invoke()
    }

    /** Registers a mounted editor without retaining its callback after the route leaves composition. */
    fun registerLeaveGuard(
        owner: Any,
        guard: (() -> Unit) -> Unit,
    ) {
        leaveGuards[owner] = guard
    }

    /** Removes only the disappearing editor's ownership; another route's guard remains intact. */
    fun removeLeaveGuard(owner: Any) {
        leaveGuards.remove(owner)
    }
}

internal val LocalShellPlaybackHost = staticCompositionLocalOf<ShellPlaybackHost?> { null }

/** Dirty editors participate in source navigation with the same confirmation used by their normal Back action. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackSourceLeaveGuard(guard: (() -> Unit) -> Unit) {
    val host = LocalShellPlaybackHost.current
    val current = rememberUpdatedState(guard)
    DisposableEffect(host) {
        val owner = Any()
        var attached = true
        host?.registerLeaveGuard(owner) { next ->
            current.value { if (attached) next() }
        }
        onDispose {
            attached = false
            host?.removeLeaveGuard(owner)
        }
    }
}
