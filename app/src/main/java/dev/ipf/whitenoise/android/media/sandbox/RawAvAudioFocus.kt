package dev.ipf.whitenoise.android.media.sandbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import dev.ipf.whitenoise.android.audio.AudioFocusOwner

/** Platform focus/route callbacks retain the exact playback owner's generation. */
internal class RawAvAudioFocus(
    private val context: Context,
    private val alive: () -> Boolean,
    private val onChange: (Int) -> Unit,
    private val onPause: (Int) -> Unit,
    private val routeOwner: Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var token = 0L
    private var noisyEnabled = true
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && alive()) {
                    onPause(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                }
            }
        }

    fun acquire(attributes: AudioAttributes): Boolean {
        val request = ++token
        return AudioFocusOwner.acquireWithFocusChanges(
            AudioFocusOwner.Owner.Video,
            attributes,
            AudioManager.AUDIOFOCUS_GAIN,
            onFocusChange = { change -> main.post { if (alive() && token == request) onChange(change) } },
            onOwnerSurrender = {
                main.post {
                    if (alive() && token == request) onPause(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                }
            },
        )
    }

    /** The engine checks process-wide ownership before asking this helper to release focus. */
    fun release() = AudioFocusOwner.release(AudioFocusOwner.Owner.Video)

    fun noisy(
        enabled: Boolean,
        playing: Boolean,
    ) {
        noisyEnabled = enabled
        if (!enabled) {
            paused()
        } else if (playing) {
            started()
        }
    }

    fun started() {
        if (!routeOwner || !noisyEnabled || registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun paused() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { paused() }
            return
        }
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { close() }
            return
        }
        token++
        paused()
    }
}
