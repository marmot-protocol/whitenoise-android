package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.state.PinnedShortcutLockDecision
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.pinnedShortcutLockDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Parses launcher pin taps under the App Lock decision. A tap that arrives while the decision still loads
 * its unlock timestamp is held, not downgraded: once the decision exists the held tap is parsed again, so
 * a lock that shows sends it to Chats and a cleared one routes it, independent of how long the engine
 * takes to start.
 */
internal class PinnedShortcutTapGate(
    private val appState: WhiteNoiseAppState,
) {
    private var held: Intent? = null

    /** True while the lock decision for this pin tap is still loading; the tap is retained for [release]. */
    fun hold(intent: Intent?): Boolean {
        if (intent?.action != PinnedConversationNavigation.ACTION_OPEN) return false
        appState.maybeShowAppLockForForeground()
        val waiting = appState.pinnedShortcutLockDecision() == PinnedShortcutLockDecision.WAIT
        if (waiting) held = intent
        return waiting
    }

    /** Hands back the held tap once; null when nothing waits. */
    fun release(): Intent? = held.also { held = null }

    /** Parses a pin tap under the decided lock state; a showing lock downgrades it to the app root. */
    fun target(
        context: Context,
        intent: Intent?,
    ): NotificationTarget? =
        PinnedConversationNavigation.target(
            context,
            intent,
            appState.activeAccountRef,
            appState.pinnedShortcutLockDecision() == PinnedShortcutLockDecision.LOCKED,
        )

    /** Replays a held tap through [replay] each time the lock decision settles; the owner re-parses it. */
    fun replayWhenDecided(
        scope: CoroutineScope,
        replay: (Intent) -> Unit,
    ): Job =
        scope.launch {
            snapshotFlow { appState.appUnlockEvaluationPending }.collect { pending ->
                if (!pending) release()?.let(replay)
            }
        }
}
