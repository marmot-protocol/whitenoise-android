package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** One capture-scoped focus request; the recognizer's later transcript drain never owns media focus. */
internal class ConversationDictationAudioFocusLease(
    private val requestFocus: (onChange: (Int) -> Unit, onSurrender: () -> Unit) -> Boolean,
    private val abandonFocus: () -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
    private val endCapture: () -> Unit,
) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var captureEndRequested = false

    /** A second acquisition without a capture close is a lifecycle error, not a new focus lease. */
    fun acquire(): Boolean {
        val generation =
            synchronized(lock) {
                if (activeGeneration != null) return false
                (++nextGeneration).also {
                    activeGeneration = it
                    captureEndRequested = false
                }
            }
        val granted =
            runCatching {
                requestFocus(
                    { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            -> requestCaptureEnd(generation)
                            // Duck and its paired gain do not restart or terminate microphone capture.
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                            AudioManager.AUDIOFOCUS_GAIN,
                            -> Unit
                        }
                    },
                    { requestCaptureEnd(generation) },
                )
            }.getOrDefault(false)
        if (!granted) {
            synchronized(lock) {
                if (activeGeneration == generation) activeGeneration = null
            }
        }
        return granted
    }

    /** Abandon once, before validation or delivery; disabled sessions never call this. */
    fun release() {
        val owned =
            synchronized(lock) {
                if (activeGeneration == null) false else {
                    activeGeneration = null
                    true
                }
            }
        if (owned) abandonFocus()
    }

    private fun requestCaptureEnd(generation: Long) {
        val shouldPost =
            synchronized(lock) {
                if (activeGeneration != generation || captureEndRequested) false else {
                    captureEndRequested = true
                    true
                }
            }
        if (shouldPost) {
            postToMain {
                val stillOwned = synchronized(lock) { activeGeneration == generation }
                if (stillOwned) endCapture()
            }
        }
    }

    companion object {
        /** The shared arbiter keeps voice playback, TTS, and dictation from owning focus simultaneously. */
        fun android(
            context: Context,
            endCapture: () -> Unit,
        ): ConversationDictationAudioFocusLease {
            val mainHandler = Handler(Looper.getMainLooper())
            return ConversationDictationAudioFocusLease(
                requestFocus = { onChange, onSurrender ->
                    AudioFocusOwner.attach(context)
                    AudioFocusOwner.acquireWithFocusChanges(
                        owner = AudioFocusOwner.Owner.Dictation,
                        audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                        focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                        onFocusChange = onChange,
                        onOwnerSurrender = onSurrender,
                    )
                },
                abandonFocus = { AudioFocusOwner.release(AudioFocusOwner.Owner.Dictation) },
                postToMain = { mainHandler.post(it) },
                endCapture = endCapture,
            )
        }
    }
}
