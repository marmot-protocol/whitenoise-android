package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.ipf.whitenoise.android.state.StalenessGuard

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
    private var onFocusChange: ((Int) -> Unit)? = null
    private var onSurrender: (() -> Unit)? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private val focusCallbacks = StalenessGuard()
    private var activeCallbacks = 0
    private val focusLock = Any()

    fun attach(context: Context) {
        synchronized(focusLock) {
            audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
        }
    }

    fun acquire(
        owner: Owner,
        audioAttributes: AudioAttributes,
        focusGain: Int,
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean =
        acquireWithFocusChanges(
            owner = owner,
            audioAttributes = audioAttributes,
            focusGain = focusGain,
            onFocusChange = { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> onFocusLoss()
                }
            },
            onOwnerSurrender = onOwnerSurrender,
        )

    /** Acquires focus while preserving the specific loss kind and paired gain. */
    fun acquireWithFocusChanges(
        owner: Owner,
        audioAttributes: AudioAttributes,
        focusGain: Int,
        onFocusChange: (Int) -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean {
        val result =
            synchronized(focusLock) {
                acquireLocked(owner, audioAttributes, focusGain, onFocusChange, onOwnerSurrender)
            }
        try {
            result.previousOwnerSurrender?.invoke()
        } catch (error: Throwable) {
            runCatching {
                synchronized(focusLock) {
                    if (currentOwner == owner) {
                        abandonFocusInternal()
                    }
                }
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        } finally {
            if (result.previousOwnerSurrender != null) {
                completeCallback()
            }
        }
        return result.acquired
    }

    /** Acquires focus and stamps the callbacks that belong to the accepted owner. */
    private fun acquireLocked(
        owner: Owner,
        audioAttributes: AudioAttributes,
        focusGain: Int,
        onFocusChange: (Int) -> Unit,
        onOwnerSurrender: () -> Unit,
    ): AcquisitionResult {
        if (activeCallbacks > 0) {
            return AcquisitionResult(acquired = false)
        }
        val am = audioManager
        if (am == null) {
            val previousSurrender = onSurrender.takeIf { currentOwner != null && currentOwner != owner }
            if (previousSurrender != null) {
                activeCallbacks += 1
            }
            currentOwner = owner
            focusCallbacks.advance()
            this.onFocusChange = onFocusChange
            onSurrender = onOwnerSurrender
            return AcquisitionResult(acquired = true, previousOwnerSurrender = previousSurrender)
        }
        if (focusRequest != null && currentOwner == owner) {
            this.onFocusChange = onFocusChange
            onSurrender = onOwnerSurrender
            return AcquisitionResult(acquired = true)
        }
        var generation: Long? = null
        val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                val focusChangeCallback =
                    synchronized(focusLock) {
                        if (generation?.let(focusCallbacks::isCurrent) != true || currentOwner != owner) {
                            null
                        } else {
                            when (change) {
                                AudioManager.AUDIOFOCUS_LOSS,
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                                AudioManager.AUDIOFOCUS_GAIN,
                                -> this.onFocusChange
                                else -> null
                            }?.also { activeCallbacks += 1 }
                        }
                    }
                try {
                    focusChangeCallback?.invoke(change)
                } finally {
                    if (focusChangeCallback != null) {
                        completeCallback()
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
        if (!granted) {
            return AcquisitionResult(acquired = false)
        }
        generation = focusCallbacks.advance()

        val previousRequest = focusRequest
        val previousSurrender = onSurrender.takeIf { currentOwner != null && currentOwner != owner }
        if (previousSurrender != null) {
            activeCallbacks += 1
        }
        focusRequest = req
        currentOwner = owner
        this.onFocusChange = onFocusChange
        onSurrender = onOwnerSurrender
        focusListener = listener
        previousRequest?.let(am::abandonAudioFocusRequest)
        return AcquisitionResult(acquired = true, previousOwnerSurrender = previousSurrender)
    }

    private data class AcquisitionResult(
        val acquired: Boolean,
        val previousOwnerSurrender: (() -> Unit)? = null,
    )

    private fun completeCallback() {
        synchronized(focusLock) {
            check(activeCallbacks > 0)
            activeCallbacks -= 1
        }
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
        synchronized(focusLock) {
            if (currentOwner != owner) return
            abandonFocusInternal()
        }
    }

    fun releaseTts() {
        release(Owner.Tts)
    }

    /** Releases platform focus and invalidates every callback from the former owner. */
    private fun abandonFocusInternal() {
        val request = focusRequest
        audioManager?.let { am ->
            request?.let { am.abandonAudioFocusRequest(it) }
        }
        focusRequest = null
        currentOwner = null
        onFocusChange = null
        onSurrender = null
        focusListener = null
        focusCallbacks.advance()
    }
}
