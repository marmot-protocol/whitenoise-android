package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioManager
import dev.ipf.whitenoise.android.audio.AudioFocusOwner

/**
 * Audio-focus ownership for read-aloud, held through the process-wide
 * [AudioFocusOwner] so read-aloud, voice messages and recording hand off to one
 * another instead of competing for platform focus. Ordinary long-form speech
 * keeps full GAIN, while the explicit media-mix policy leaves platform focus
 * untouched so speech and external media can remain active together. Every loss
 * pauses the queue at its retained position rather than destroying the session
 * (#1484). Permanent loss releases the spent request so resume() asks again.
 */
internal class TtsAudioFocusOwner(
    context: Context,
) : TtsAudioFocus {
    init {
        AudioFocusOwner.attach(context)
    }

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
                release()
                true
            }
            TtsAudioFocusMode.Full ->
                AudioFocusOwner.acquireWithFocusChanges(
                    owner = AudioFocusOwner.Owner.Tts,
                    audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                    focusGain = AudioManager.AUDIOFOCUS_GAIN,
                    onFocusChange = { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                            -> onFocusLoss()
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                release()
                                onOwnerSurrender()
                            }
                        }
                    },
                    onOwnerSurrender = onOwnerSurrender,
                )
        }

    /** Abandons read-aloud focus if read-aloud still holds it. */
    override fun release() {
        AudioFocusOwner.release(AudioFocusOwner.Owner.Tts)
    }
}
