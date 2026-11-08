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
    /** Unavailable on synthetic devices; Android reports the actual AudioTrack counter. */
    val underruns: Int get() = -1
    val normalFrames: Int
    val capacityFrames: Int
    val legacyPriming: Boolean

    /**
     * Acknowledges a one-frame start threshold where supported; older devices use the explicit priming path.
     */
    fun startThresholdOne(): Boolean

    /** Returns the device-accepted frame capacity so the queue can reject an incomplete resize. */
    fun resize(frames: Int): Int

    /** Returns the hardware playback-head counter; the owning sink extends its unsigned wraparound. */
    fun headPosition(): Int

    /** Attempts a nonblocking PCM-buffer write and reports only the bytes the device accepted. */
    fun write(
        buffer: ByteBuffer,
        byteCount: Int,
    ): Int

    /** Attempts a nonblocking write of queue-owned priming silence, separate from source PCM accounting. */
    fun writeSilence(
        bytes: ByteArray,
        byteCount: Int,
    ): Int

    /** Starts the owned raw-PCM device without enabling a compressed-media parser. */
    fun play()

    /** Pauses the owned raw-PCM device while retaining its buffers and clock for a later resume. */
    fun pause()

    /** Sets the device gain; the Android implementation reports a rejected gain as a typed output fault. */
    fun volume(value: Float)
}

@Suppress("TooManyFunctions") // One synchronous AudioTrack implements the fixed testable PCM device boundary.
internal class AndroidRawPcmDevice(
    rate: Int,
    channels: Int,
    attributes: AudioAttributes,
) : RawPcmDevice {
    override val underruns: Int get() = track.underrunCount
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

    /**
     * Acknowledges a one-frame start threshold where supported; older devices use the explicit priming path.
     */
    override fun startThresholdOne(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return track.setStartThresholdInFrames(1) == 1
    }

    /** Returns the device-accepted frame capacity so the queue can reject an incomplete resize. */
    override fun resize(frames: Int): Int = track.setBufferSizeInFrames(frames)

    /** Returns the hardware playback-head counter; the owning sink extends its unsigned wraparound. */
    override fun headPosition(): Int = track.playbackHeadPosition

    /** Attempts a nonblocking PCM-buffer write and reports only the bytes the device accepted. */
    override fun write(
        buffer: ByteBuffer,
        byteCount: Int,
    ): Int = track.write(buffer, byteCount, AudioTrack.WRITE_NON_BLOCKING)

    /** Attempts a nonblocking write of queue-owned priming silence, separate from source PCM accounting. */
    override fun writeSilence(
        bytes: ByteArray,
        byteCount: Int,
    ): Int = track.write(bytes, 0, byteCount, AudioTrack.WRITE_NON_BLOCKING)

    /** Starts the owned raw-PCM device without enabling a compressed-media parser. */
    override fun play() = track.play()

    /** Pauses the owned raw-PCM device while retaining its buffers and clock for a later resume. */
    override fun pause() = track.pause()

    /** Sets the device gain; the Android implementation reports a rejected gain as a typed output fault. */
    override fun volume(value: Float) {
        if (track.setVolume(value) != AudioTrack.SUCCESS) throw RawOutputFailure("Raw audio volume refused")
    }

    /** Flushes and releases the exact AudioTrack, with release attempted even if flushing fails. */
    override fun close() {
        try {
            track.flush()
        } finally {
            track.release()
        }
    }
}
