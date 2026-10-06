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
    private val lockDecision: () -> PinnedShortcutLockDecision,
    private val activeAccount: () -> String?,
    private val evaluationPending: () -> Boolean,
    private val evaluateForeground: () -> Unit = {},
) {
    /** Production ownership stays in AppState; callbacks let routing interleavings use the actual gate in tests. */
    constructor(appState: WhiteNoiseAppState) : this(
        lockDecision = { appState.pinnedShortcutLockDecision() },
        activeAccount = { appState.activeAccountRef },
        evaluationPending = { appState.appUnlockEvaluationPending },
        evaluateForeground = { appState.maybeShowAppLockForForeground() },
    )

    private var held: Intent? = null

    /** True while the lock decision for this pin tap is still loading; the tap is retained for [release]. */
    fun hold(intent: Intent?): Boolean {
        if (intent?.action != PinnedConversationNavigation.ACTION_OPEN) return false
        evaluateForeground()
        val waiting = lockDecision() == PinnedShortcutLockDecision.WAIT
        held = if (waiting) intent else null
        return waiting
    }

    /** Hands back the held tap once; null when nothing waits. */
    fun release(): Intent? = held.also { held = null }

    /** A newer accepted route owns navigation, including while a pin waits for the lock decision. */
    fun supersede() {
        held = null
    }

    /** Mirrors accepted inbound routing without discarding a pin for a harmless dataless launcher intent. */
    fun supersedeForRoute(
        hasTarget: Boolean,
        hasShare: Boolean,
        profileData: String?,
    ) {
        if (hasTarget || hasShare || profileData != null) supersede()
    }

    /** Parses a pin tap under the decided lock state; a showing lock downgrades it to the app root. */
    fun target(
        context: Context,
        intent: Intent?,
    ): NotificationTarget? =
        PinnedConversationNavigation.target(
            context,
            intent,
            activeAccount(),
            lockDecision() == PinnedShortcutLockDecision.LOCKED,
        )

    /** Replays a held tap through [replay] each time the lock decision settles; the owner re-parses it. */
    fun replayWhenDecided(
        scope: CoroutineScope,
        replay: (Intent) -> Unit,
    ): Job =
        scope.launch {
            snapshotFlow { evaluationPending() }.collect { pending ->
                if (!pending) release()?.let(replay)
            }
        }
}
