package dev.ipf.whitenoise.android

import android.app.Application
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

class WhiteNoiseApplication : Application() {
    val appState: WhiteNoiseAppState by lazy {
        WhiteNoiseAppState(this)
    }

    override fun onCreate() {
        super.onCreate()
        VoicePlaybackController.attach(this)
    }
}
