package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio-focus ownership for read-aloud. Ordinary long-form speech keeps full
 * GAIN, while the explicit media-mix policy leaves platform focus untouched so
 * speech and external media can remain active together. Full-focus loss remains
 * survivable: transient and permanent loss pause the queue at its retained
 * position rather than destroying the session (#1484). The callbacks stay
 * separate so permanent loss can drop the spent request for resume().
 */
internal class TtsAudioFocusOwner(
    context: Context,
) : TtsAudioFocus {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var focusMode: TtsAudioFocusMode? = null
    private var focusGeneration = 0L

    /** Preserves the established full-gain behavior for ordinary read-aloud. */
    override fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean = acquire(TtsAudioFocusMode.Full, onFocusLoss, onOwnerSurrender)

    /** Owns full focus for ordinary speech and records MediaMix without requesting focus. */
    override fun acquire(
        mode: TtsAudioFocusMode,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean =
        when (mode) {
            TtsAudioFocusMode.MediaMix -> {
                if (focusMode != null && focusMode != mode) release()
                focusMode = mode
                true
            }
            TtsAudioFocusMode.Full -> acquireFullFocus(onFocusLoss, onOwnerSurrender)
        }

    /** Requests or retains the ordinary full-gain focus request. */
    private fun acquireFullFocus(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean =
        audioManager?.let { manager ->
            if (focusMode != null && focusMode != TtsAudioFocusMode.Full) release()
            if (focusRequest == null) {
                focusGeneration += 1L
                val requestGeneration = focusGeneration
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
                        .setOnAudioFocusChangeListener focusChange@{ change ->
                            if (requestGeneration != focusGeneration) return@focusChange
                            when (change) {
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                                -> onFocusLoss()
                                AudioManager.AUDIOFOCUS_LOSS -> {
                                    focusRequest = null
                                    focusMode = null
                                    focusGeneration += 1L
                                    onOwnerSurrender()
                                }
                                else -> Unit
                            }
                        }.build()
                if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focusRequest = request
                    focusMode = TtsAudioFocusMode.Full
                }
            }
            focusRequest != null
        } ?: false

    /** Abandons the exact request held by this owner. */
    override fun release() {
        val request = focusRequest
        focusRequest = null
        focusMode = null
        focusGeneration += 1L
        val manager = audioManager ?: return
        request?.let { manager.abandonAudioFocusRequest(it) }
    }
}
