package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio-focus ownership for read-aloud. Ordinary long-form speech keeps full
 * GAIN. The separate, explicit media-mix policy requests transient MAY_DUCK so
 * White Noise never pauses or controls the external player itself. Both loss
 * kinds remain survivable: transient and permanent loss pause the queue at its
 * retained position rather than destroying the session (#1484). The callbacks
 * stay separate so permanent loss can drop the spent request for resume().
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

    /** Builds the single focus request whose gain matches the selected session policy. */
    override fun acquire(
        mode: TtsAudioFocusMode,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean {
        val manager = audioManager ?: return false
        if (focusRequest != null && focusMode != mode) release()
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
                    .Builder(
                        if (mode == TtsAudioFocusMode.MediaMix) {
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                        } else {
                            AudioManager.AUDIOFOCUS_GAIN
                        },
                    ).setAudioAttributes(attributes)
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
                focusMode = mode
            }
        }
        return focusRequest != null
    }

    /** Abandons the exact request held by this owner. */
    override fun release() {
        val manager = audioManager ?: return
        val request = focusRequest
        focusRequest = null
        focusMode = null
        focusGeneration += 1L
        request?.let { manager.abandonAudioFocusRequest(it) }
    }
}
