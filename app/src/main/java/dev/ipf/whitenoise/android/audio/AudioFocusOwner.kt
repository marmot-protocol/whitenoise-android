package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Process-wide audio-focus arbiter shared by voice-note playback (#1479) and
 * the future TTS controller (#1480). Only one owner holds focus at a time;
 * handoff occurs only after the new owner is granted focus.
 */
object AudioFocusOwner {
    enum class Owner {
        Voice,
        Tts,
    }

    val ttsSpeechAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentOwner: Owner? = null
    private var onLoss: (() -> Unit)? = null
    private var onSurrender: (() -> Unit)? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var nextGeneration = 0L
    private var currentGeneration = 0L

    fun attach(context: Context) {
        audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    }

    fun acquire(
        owner: Owner,
        audioAttributes: AudioAttributes,
        focusGain: Int,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean {
        val am = audioManager
        if (am == null) {
            if (currentOwner != null && currentOwner != owner) {
                onSurrender?.invoke()
            }
            currentOwner = owner
            onLoss = onFocusLoss
            onSurrender = onOwnerSurrender
            return true
        }
        if (focusRequest != null && currentOwner == owner) {
            onLoss = onFocusLoss
            onSurrender = onOwnerSurrender
            return true
        }
        val generation = ++nextGeneration
        val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                if (generation == currentGeneration && currentOwner == owner) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        -> onLoss?.invoke()
                    }
                }
            }
        val req =
            AudioFocusRequest
                .Builder(focusGain)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(listener)
                .build()
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            val previousRequest = focusRequest
            val previousSurrender = onSurrender.takeIf { currentOwner != null && currentOwner != owner }
            focusRequest = req
            currentOwner = owner
            onLoss = onFocusLoss
            onSurrender = onOwnerSurrender
            focusListener = listener
            currentGeneration = generation
            try {
                previousSurrender?.invoke()
            } finally {
                previousRequest?.let(am::abandonAudioFocusRequest)
            }
        }
        return granted
    }

    fun acquireForTts(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean =
        acquire(
            owner = Owner.Tts,
            audioAttributes = ttsSpeechAttributes,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            onFocusLoss = onFocusLoss,
            onOwnerSurrender = onOwnerSurrender,
        )

    fun release(owner: Owner) {
        if (currentOwner != owner) return
        abandonFocusInternal()
    }

    fun releaseTts() {
        release(Owner.Tts)
    }

    private fun abandonFocusInternal() {
        val request = focusRequest
        audioManager?.let { am ->
            request?.let { am.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
        currentOwner = null
        onLoss = null
        onSurrender = null
        focusListener = null
        currentGeneration = 0L
    }
}
