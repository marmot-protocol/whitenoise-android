package dev.ipf.whitenoise.android.audio

import android.content.Context
import dev.ipf.whitenoise.android.media.sandbox.MediaAdmissionFailure
import dev.ipf.whitenoise.android.media.sandbox.RawOutputFailure
import java.io.File
import java.io.IOException

/** Voice controls use cached presentation state; compressed audio never enters this owner. */
internal interface VoicePlaybackPlayer {
    val isPlaying: Boolean
    val duration: Int
    val currentPosition: Int

    /**
     * Starts or resumes the prepared player after the controller has obtained voice focus.
     */
    fun start()

    /**
     * Pauses playback while retaining the prepared player for later resume.
     */
    fun pause()

    /**
     * Stops the current playback session; callers release the player when finished with it.
     */
    fun stop()

    /**
     * Releases player resources and invalidates subsequent playback control.
     */
    fun release()

    /**
     * Requests a source position in milliseconds; successful completion is delivered to the seek listener.
     */
    fun seekTo(positionMs: Int)

    /**
     * Sets the playback rate ratio; supported bounds are enforced by the implementation.
     */
    fun setSpeed(speed: Float)

    /**
     * Sets normalized left/right gains; an implementation may require equal channel gains.
     */
    fun setVolume(
        left: Float,
        right: Float,
    )

    /**
     * Installs the callback for completed playback on this player.
     */
    fun setOnCompletionListener(listener: (VoicePlaybackPlayer) -> Unit)

    /**
     * Installs the callback for a successfully completed seek on this player.
     */
    fun setOnSeekCompleteListener(listener: (VoicePlaybackPlayer) -> Unit)

    /**
     * Installs a listener receiving the typed primary error code and implementation detail code.
     */
    fun setOnErrorListener(listener: (VoicePlaybackPlayer, Int, Int) -> Boolean)

    /** Preparation runs off Main and returns one owned player or throws without retaining it. */
    fun interface Factory {
        /**
         * Returns an owned prepared player off Main or throws after cleaning up partial preparation.
         */
        fun prepare(
            context: Context,
            file: File,
        ): VoicePlaybackPlayer
    }

    companion object {
        const val SOURCE_ERROR = 1
        const val OUTPUT_ERROR = -10000
        const val ADMISSION_ERROR = -10001
    }
}

/**
 * Separates source corruption from device-output and admission failures for voice error handling.
 */
internal fun voicePlaybackErrorCode(failure: IOException): Int =
    when (failure) {
        is RawOutputFailure -> VoicePlaybackPlayer.OUTPUT_ERROR
        is MediaAdmissionFailure -> VoicePlaybackPlayer.ADMISSION_ERROR
        else -> VoicePlaybackPlayer.SOURCE_ERROR
    }

/**
 * Keeps healthy cached media for output/admission refusal; other error codes invalidate it.
 */
internal fun voicePlaybackErrorInvalidatesCache(code: Int): Boolean {
    val outputAvailable = code != VoicePlaybackPlayer.OUTPUT_ERROR
    return outputAvailable && code != VoicePlaybackPlayer.ADMISSION_ERROR
}
