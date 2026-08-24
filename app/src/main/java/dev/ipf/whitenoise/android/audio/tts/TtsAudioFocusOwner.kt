package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio-focus ownership for read-aloud. Spoken messages are long-form
 * content, so this requests full GAIN (not transient). Both loss kinds are
 * survivable: a transient loss — a notification chime, a voice note starting —
 * and a permanent loss (another app takes over playback) pause the queue at
 * its retained position rather than destroying the session; the paused
 * session stays resumable until it is explicitly dismissed (#1484). The two
 * callbacks stay separate so the permanent path can also drop the spent
 * focus request, which a later resume() re-acquires from scratch.
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
        if (focusRequest == null) {
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
            if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
            }
        }
        return focusRequest != null
    }

    override fun release() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
