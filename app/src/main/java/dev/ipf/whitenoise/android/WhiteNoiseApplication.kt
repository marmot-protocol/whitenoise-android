package dev.ipf.whitenoise.android

import android.app.Application
import android.util.Log
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.state.DisappearingMessageSweepWorker
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.applyApplicationLanguageTag
import dev.ipf.whitenoise.android.state.persistedApplicationLanguageTag
import dev.ipf.whitenoise.android.ui.createRecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.navigation.MainShellProcessState
import dev.ipf.whitenoise.android.updates.AppUpdateWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class WhiteNoiseApplication : Application() {
    val appState: WhiteNoiseAppState by lazy {
        WhiteNoiseAppState(this)
    }

    val recentEmojiRecentsOwner by lazy {
        createRecentEmojiRecentsOwner()
    }

    private val mainShellProcessStateDelegate =
        lazy {
            MainShellProcessState(appState)
        }
    internal val mainShellProcessState: MainShellProcessState by mainShellProcessStateDelegate

    /** Reset task-owned UI state without discarding the process-warm chat list. */
    internal fun onTaskRemoved() {
        if (mainShellProcessStateDelegate.isInitialized()) {
            mainShellProcessState.onTaskRemoved()
        }
        appState.onTaskRemoved()
    }

    /**
     * Process-lifetime scope for short fire-and-forget work that must survive
     * a component's teardown — e.g. a stopping service recording durable
     * state. Owning it here keeps such work off unowned per-call scopes. The
     * handler keeps one bad future launch from crashing the whole process:
     * SupervisorJob isolates siblings but does not swallow exceptions.
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    Log.w("WhiteNoiseApplication", "unhandled applicationScope failure", throwable)
                },
        )

    override fun onCreate() {
        super.onCreate()
        // AppCompat must receive custom-stored locales before MainActivity's
        // onCreate on API 32 and lower so it can wrap the Activity context.
        applyApplicationLanguageTag(persistedApplicationLanguageTag(this))
        VoicePlaybackController.attach(this)
        // Coarse background prune of expired disappearing messages in closed
        // conversations (#745). KEEP-policy unique work, so this just ensures
        // the schedule exists without resetting an already-running cadence.
        DisappearingMessageSweepWorker.schedule(this)
        AppUpdateWorker.schedule(this)
    }
}
