package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio-focus ownership for read-aloud. Spoken messages are long-form
 * content, so this requests full GAIN (not transient): a transient loss —
 * a notification chime, a voice note starting — pauses the queue for the
 * user to resume; a permanent loss (another app takes over playback) ends
 * the session outright rather than parking a stale paused bar forever.
 */
internal class TtsAudioFocusOwner(
    context: Context,
) : TtsAudioFocus {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    override fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean {
        val manager = audioManager ?: return false
        focusRequest?.let { return true }
        val attributes =
            AudioAttributes
                .Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        -> onFocusLoss()
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            focusRequest = null
                            onOwnerSurrender()
                        }
                        else -> Unit
                    }
                }.build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    override fun release() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
