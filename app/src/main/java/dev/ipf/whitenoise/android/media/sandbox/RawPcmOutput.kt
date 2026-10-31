@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/** Parent-side PCM sink: no file, container, codec or worker-controlled allocation metadata enters AudioTrack. */
@Suppress("TooManyFunctions") // One AudioTrack owns its bounded writes, clock and retirement controls.
internal class RawPcmOutput(
    override val sampleRate: Int,
    override val channels: Int,
    attributes: AudioAttributes,
) : RawPcmSink {
    private val frameBytes = channels * Short.SIZE_BYTES
    private val track: AudioTrack
    private var lastHead = 0L
    private var headWrap = 0L
    private var writtenFrames = 0L
    private var closed = false

    init {
        require(sampleRate in 8_000..96_000 && channels in 1..2)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val capacity = RawPcmCapacity.bytes(minimum, sampleRate, channels)
        track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(capacity)
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw RawOutputFailure("Raw audio output unavailable")
        }
    }

    /** A nonblocking write cannot pin retirement behind a full device queue. */
    @Synchronized override fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        if (closed) throw RawOutputFailure("Raw audio output retired")
        require(bytes.size <= MAX_CHUNK_BYTES && bytes.size % frameBytes == 0)
        require(offset in 0..bytes.size && offset % frameBytes == 0)
        val count = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
        if (count < 0 || count % frameBytes != 0) throw RawOutputFailure("Raw audio output failed")
        writtenFrames += count / frameBytes
        return count
    }

    @Synchronized override fun play() {
        if (!closed) track.play()
    }

    @Synchronized override fun pause() {
        if (!closed) track.pause()
    }

    @Synchronized override fun speed(value: Float) {
        if (closed) return
        require(value.isFinite() && value in 0.5f..RawPcmCapacity.MAX_SPEED)
        track.playbackParams = PlaybackParams().setSpeed(value).setPitch(1f)
    }

    @Synchronized override fun volume(value: Float) {
        if (closed) return
        require(value.isFinite() && value in 0f..1f)
        if (track.setVolume(value) != AudioTrack.SUCCESS) throw RawOutputFailure("Raw audio volume refused")
    }

    @Synchronized fun playedFrames(): Long {
        if (closed) return headWrap + lastHead
        val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
        if (head < lastHead) headWrap += 1L shl 32
        lastHead = head
        return headWrap + head
    }

    @Synchronized override fun drained(): Boolean = closed || playedFrames() >= writtenFrames

    @Synchronized override fun writtenPositionUs(): Long = writtenFrames * 1_000_000L / sampleRate

    @Synchronized override fun positionUs(): Long = playedFrames() * 1_000_000L / sampleRate

    @Synchronized fun flush() {
        if (closed) return
        track.pause()
        track.flush()
        lastHead = 0
        headWrap = 0
        writtenFrames = 0
    }

    @Synchronized override fun close() {
        if (closed) return
        runCatching { playedFrames() }
        closed = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 256 * 1024
    }
}
