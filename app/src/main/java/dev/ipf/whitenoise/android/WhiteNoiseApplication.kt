package dev.ipf.whitenoise.android

import android.app.Application
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.state.DisappearingMessageSweepWorker
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

class WhiteNoiseApplication : Application() {
    val appState: WhiteNoiseAppState by lazy {
        WhiteNoiseAppState(this)
    }

    override fun onCreate() {
        super.onCreate()
        VoicePlaybackController.attach(this)
        // Coarse background prune of expired disappearing messages in closed
        // conversations (#745). KEEP-policy unique work, so this just ensures
        // the schedule exists without resetting an already-running cadence.
        DisappearingMessageSweepWorker.schedule(this)
    }
}
