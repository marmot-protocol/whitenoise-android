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

    fun start()

    fun pause()

    fun stop()

    fun release()

    fun seekTo(positionMs: Int)

    fun setSpeed(speed: Float)

    fun setVolume(
        left: Float,
        right: Float,
    )

    fun setOnCompletionListener(listener: (VoicePlaybackPlayer) -> Unit)

    fun setOnSeekCompleteListener(listener: (VoicePlaybackPlayer) -> Unit)

    fun setOnErrorListener(listener: (VoicePlaybackPlayer, Int, Int) -> Boolean)

    /** Preparation runs off Main and returns one owned player or throws without retaining it. */
    fun interface Factory {
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

internal fun voicePlaybackErrorCode(failure: IOException): Int =
    when (failure) {
        is RawOutputFailure -> VoicePlaybackPlayer.OUTPUT_ERROR
        is MediaAdmissionFailure -> VoicePlaybackPlayer.ADMISSION_ERROR
        else -> VoicePlaybackPlayer.SOURCE_ERROR
    }

internal fun voicePlaybackErrorInvalidatesCache(code: Int): Boolean {
    val outputAvailable = code != VoicePlaybackPlayer.OUTPUT_ERROR
    return outputAvailable && code != VoicePlaybackPlayer.ADMISSION_ERROR
}
