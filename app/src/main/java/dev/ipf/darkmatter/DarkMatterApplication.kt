package dev.ipf.darkmatter

import android.app.Application
import dev.ipf.darkmatter.audio.VoicePlaybackController
import dev.ipf.darkmatter.state.DarkMatterAppState

class DarkMatterApplication : Application() {
    val appState: DarkMatterAppState by lazy {
        DarkMatterAppState(this)
    }

    override fun onCreate() {
        super.onCreate()
        VoicePlaybackController.attach(this)
    }
}
