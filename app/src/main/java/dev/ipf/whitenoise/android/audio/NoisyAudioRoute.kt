package dev.ipf.whitenoise.android.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers for the platform's headphone-disconnect signal ([AudioManager.ACTION_AUDIO_BECOMING_NOISY]).
 * Each registration is closed by its owner once playback can no longer produce sound.
 */
internal fun interface NoisyAudioRouteRegistrar {
    /** Starts listening; [onNoisy] never runs after the returned registration is closed. */
    fun register(onNoisy: () -> Unit): AutoCloseable

    companion object {
        /** No platform listener, for owners constructed without a context. */
        val None = NoisyAudioRouteRegistrar { AutoCloseable {} }
    }
}

/** Delivers the system broadcast on the main thread through a non-exported receiver. */
internal class AndroidNoisyAudioRouteRegistrar(
    context: Context,
) : NoisyAudioRouteRegistrar {
    private val appContext = context.applicationContext

    override fun register(onNoisy: () -> Unit): AutoCloseable {
        val closed = AtomicBoolean(false)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    // A broadcast already queued on Main can land after close.
                    if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !closed.get()) onNoisy()
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
}

/** Holds at most one registration whose callback is retired as soon as it is disarmed. */
internal class NoisyAudioRouteListener(
    private val registrar: () -> NoisyAudioRouteRegistrar,
    private val onNoisy: () -> Unit,
) {
    private var registration: AutoCloseable? = null

    val armed: Boolean
        @Synchronized get() = registration != null

    @Synchronized
    fun arm() {
        if (registration == null) registration = registrar().register(onNoisy)
    }

    fun disarm() {
        val closing =
            synchronized(this) {
                registration.also { registration = null }
            }
        closing?.runCatching { close() }
    }
}
