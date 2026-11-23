package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class VoiceNotePreferences(
    private val preferences: SharedPreferences,
) {
    var sendHeldOnRelease by mutableStateOf(preferences.getBoolean(SEND_HELD_ON_RELEASE, false))
        private set
    var transcribeAutomatically by mutableStateOf(preferences.getBoolean(TRANSCRIBE_AUTOMATICALLY, false))
        private set

    fun updateSendHeldOnRelease(enabled: Boolean) {
        sendHeldOnRelease = enabled
        preferences.edit().putBoolean(SEND_HELD_ON_RELEASE, enabled).apply()
    }

    fun updateTranscribeAutomatically(enabled: Boolean) {
        transcribeAutomatically = enabled
        preferences.edit().putBoolean(TRANSCRIBE_AUTOMATICALLY, enabled).apply()
    }

    private companion object {
        const val SEND_HELD_ON_RELEASE = "voice_note_send_held_on_release"
        const val TRANSCRIBE_AUTOMATICALLY = "voice_note_transcribe_automatically"
    }
}
