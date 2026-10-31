@file:Suppress("MagicNumber") // Supported raw PCM rates and the unity hardware parameter contract.

package dev.ipf.whitenoise.android.media.sandbox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import java.nio.ByteBuffer

/** Synchronous parent PCM-only hardware operations; the output owns this exact device through close. */
internal interface RawPcmDevice : AutoCloseable {
    val normalFrames: Int
    val capacityFrames: Int
    val legacyPriming: Boolean

    fun startThresholdOne(): Boolean

    fun resize(frames: Int): Int

    fun headPosition(): Int

    fun write(
        buffer: ByteBuffer,
        byteCount: Int,
    ): Int

    fun writeSilence(
        bytes: ByteArray,
        byteCount: Int,
    ): Int

    fun play()

    fun pause()

    fun volume(value: Float)
}

@Suppress("TooManyFunctions") // One synchronous AudioTrack implements the fixed testable PCM device boundary.
internal class AndroidRawPcmDevice(
    rate: Int,
    channels: Int,
    attributes: AudioAttributes,
) : RawPcmDevice {
    private val track: AudioTrack
    override val legacyPriming: Boolean get() = Build.VERSION.SDK_INT < 31
    override val normalFrames: Int
    override val capacityFrames: Int get() = track.bufferCapacityInFrames

    init {
        require(rate in 8000..96000 && channels in 1..2)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val capacity = RawPcmCapacity.bytes(minimum, rate, channels)
        track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(capacity)
                .build()
        @Suppress("TooGenericExceptionCaught") // Device initialization refusal must release this exact track.
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw RawOutputFailure("Raw audio output unavailable")
            track.playbackParams = PlaybackParams().setSpeed(1f).setPitch(1f)
            val unity = track.playbackParams
            if (unity.speed != 1f || unity.pitch != 1f) throw RawOutputFailure("Unity raw audio output refused")
            normalFrames = track.bufferSizeInFrames
        } catch (failure: Exception) {
            runCatching { track.release() }
            throw RawOutputFailure("Raw audio device initialization refused", failure)
        }
    }

    override fun startThresholdOne(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return track.setStartThresholdInFrames(1) == 1
    }

    override fun resize(frames: Int): Int = track.setBufferSizeInFrames(frames)

    override fun headPosition(): Int = track.playbackHeadPosition

    override fun write(
        buffer: ByteBuffer,
        byteCount: Int,
    ): Int = track.write(buffer, byteCount, AudioTrack.WRITE_NON_BLOCKING)

    override fun writeSilence(
        bytes: ByteArray,
        byteCount: Int,
    ): Int = track.write(bytes, 0, byteCount, AudioTrack.WRITE_NON_BLOCKING)

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun volume(value: Float) {
        if (track.setVolume(value) != AudioTrack.SUCCESS) throw RawOutputFailure("Raw audio volume refused")
    }

    override fun close() {
        try {
            track.flush()
        } finally {
            track.release()
        }
    }
}
