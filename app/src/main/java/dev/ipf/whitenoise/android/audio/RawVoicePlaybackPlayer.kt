package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.ipf.whitenoise.android.media.sandbox.RawAvPlaybackEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** The existing voice focus owner controls one audio-only isolated decoding session. */
@Suppress("TooManyFunctions") // One adapter implements the existing voice control and listener contract.
internal class RawVoicePlaybackPlayer private constructor(
    context: Context,
    file: File,
) : VoicePlaybackPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var completion: ((VoicePlaybackPlayer) -> Unit)? = null
    private var seekComplete: ((VoicePlaybackPlayer) -> Unit)? = null
    private var error: ((VoicePlaybackPlayer, Int, Int) -> Boolean)? = null
    private var reportedError: IOException? = null

    @Volatile private var released = false
    private var seekSequence = 0L
    private val engine =
        RawAvPlaybackEngine(
            context,
            Uri.fromFile(file),
            wantVideo = false,
            wantAudio = true,
            externalAudioFocus = true,
            listener =
                object : RawAvPlaybackEngine.Listener {
                    override fun onChanged() = engineChanged()

                    override fun onCompleted() {
                        if (!released) completion?.invoke(this@RawVoicePlaybackPlayer)
                    }
                },
        ).also {
            it.setAttributes(
                AudioAttributes
                    .Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
        }

    /**
     * Delivers each distinct typed engine failure once while this voice player remains live.
     */
    private fun engineChanged() {
        val failure = engine.snapshot().error
        if (!released && failure != null && reportedError !== failure) {
            reportedError = failure
            error?.invoke(this, voicePlaybackErrorCode(failure), 0)
        }
    }

    override val isPlaying: Boolean get() = snapshot().playWhenReady
    override val duration: Int get() = snapshot().durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    override val currentPosition: Int
        get() {
            check(!released)
            return engine.positionMs().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

    /**
     * Reads cached engine state and rejects access after release.
     */
    private fun snapshot(): RawAvPlaybackEngine.Snapshot {
        check(!released) { "Voice playback is released" }
        return engine.snapshot()
    }

    /**
     * Requests playback with externally owned voice focus; a retired source refuses synchronously.
     */
    override fun start() {
        check(!released)
        if (!engine.setPlaying(true)) throw IOException("Voice playback source retired")
    }

    /**
     * Stops play-when-ready without releasing this prepared source.
     */
    override fun pause() {
        check(!released)
        engine.setPlaying(false)
    }

    /**
     * Retires current playback work through the engine while retaining the adapter object.
     */
    override fun stop() {
        check(!released)
        engine.stop()
    }

    /**
     * Invalidates pending seek callbacks, clears listeners and closes the engine once.
     */
    override fun release() {
        if (released) return
        released = true
        seekSequence++
        completion = null
        seekComplete = null
        error = null
        engine.close()
    }

    /**
     * Requests a nonnegative millisecond target and reports only the latest successful seek on Main.
     * Canceled/superseded requests do not become source failures; already-refused requests throw.
     */
    override fun seekTo(positionMs: Int) {
        check(!released)
        require(positionMs >= 0)
        val sequence = ++seekSequence
        val future = engine.seekToAsync(positionMs.toLong())
        // Preserve synchronous refusal for a request already rejected by its source owner.
        if (future.isDone) future.get()
        future.addListener(
            {
                try {
                    future.get()
                    // Even an immediate paused seek completes after the caller records its pending target.
                    main.post { if (!released && seekSequence == sequence) seekComplete?.invoke(this) }
                } catch (_: CancellationException) {
                    // Pause, superseding seeks and release cancel the exact request without a source error.
                } catch (_: ExecutionException) {
                    // The engine's generation-guarded onChanged callback delivers its typed source failure.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            { command -> command.run() },
        )
    }

    /**
     * Delegates the playback ratio to the engine's bounded speed policy.
     */
    override fun setSpeed(speed: Float) {
        check(!released)
        engine.setSpeed(speed)
    }

    /**
     * Requires equal finite left/right gain from zero to one for the single raw PCM output.
     */
    override fun setVolume(
        left: Float,
        right: Float,
    ) {
        check(!released)
        require(left == right && left.isFinite() && left in 0f..1f)
        engine.setVolume(left)
    }

    /**
     * Replaces the completion callback invoked for this live adapter when the engine completes.
     */
    override fun setOnCompletionListener(listener: (VoicePlaybackPlayer) -> Unit) {
        completion = listener
    }

    /**
     * Replaces the callback used by the latest successful, still-current seek.
     */
    override fun setOnSeekCompleteListener(listener: (VoicePlaybackPlayer) -> Unit) {
        seekComplete = listener
    }

    /**
     * Replaces the listener receiving typed source, output or admission failure codes.
     */
    override fun setOnErrorListener(listener: (VoicePlaybackPlayer, Int, Int) -> Boolean) {
        error = listener
    }

    object Factory : VoicePlaybackPlayer.Factory {
        /**
         * Prepares one audio-only engine off Main with a bounded future wait.
         * Any failed preparation closes that exact player; interruption is restored before propagation.
         */
        @Suppress("TooGenericExceptionCaught") // Any failed preparation closes its exact created engine.
        override fun prepare(context: Context, file: File): VoicePlaybackPlayer {
            check(Looper.myLooper() != Looper.getMainLooper())
            val player = RawVoicePlaybackPlayer(context.applicationContext, file)
            try {
                player.engine.prepare().get(PREPARE_WAIT_SECONDS, TimeUnit.SECONDS)
                return player
            } catch (failure: Exception) {
                player.release()
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw failure
            } catch (failure: OutOfMemoryError) {
                player.release()
                throw failure
            }
        }
    }

    private companion object {
        const val PREPARE_WAIT_SECONDS = 24L
    }
}
