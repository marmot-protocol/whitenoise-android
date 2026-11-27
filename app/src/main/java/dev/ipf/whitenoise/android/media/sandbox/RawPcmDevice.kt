@file:Suppress("MagicNumber") // Supported raw PCM rates and the unity hardware parameter contract.

package dev.ipf.whitenoise.android.media.sandbox

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Estimates the current speaker presentation frame on the same device counter, when available. */
    fun presentationFrames(mixerHead: Long): Long? = null

    /** Estimated output delay until this route provides a usable hardware timestamp. */
    val fallbackPresentationLatencyMs: Long get() = 0L
    val presentationRouteGeneration: Int get() = 0

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
    private val rate: Int,
    channels: Int,
    attributes: AudioAttributes,
) : RawPcmDevice {
    override val underruns: Int get() = track.underrunCount
    private val track: AudioTrack
    private val presentationClock = RawPcmPresentationClock(rate, ::readTimestamp)
    private var routeType: Int? = null
    override var presentationRouteGeneration = 0
        private set
    override val fallbackPresentationLatencyMs: Long
        get() =
            when (routeType) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                -> LOCAL_ROUTE_LATENCY_MS
                else -> UNKNOWN_ROUTE_LATENCY_MS
            }
    private val routeChanged = AtomicBoolean()
    private val routingListener = AudioRouting.OnRoutingChangedListener { routeChanged.set(true) }
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
            track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
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

    override fun presentationFrames(mixerHead: Long): Long? {
        if (routeChanged.getAndSet(false)) {
            presentationRouteGeneration++
            routeType = null
            presentationClock.reset()
        }
        return presentationClock.frames(System.nanoTime(), underruns, mixerHead)
    }

    private fun readTimestamp(timestamp: AudioTimestamp): Boolean {
        if (routeType == null) routeType = runCatching { track.routedDevice?.type }.getOrNull()
        return track.getTimestamp(timestamp)
    }

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
    override fun play() {
        presentationClock.reset()
        track.play()
    }

    /** Pauses the owned raw-PCM device while retaining its buffers and clock for a later resume. */
    override fun pause() = track.pause()

    /** Sets the device gain; the Android implementation reports a rejected gain as a typed output fault. */
    override fun volume(value: Float) {
        if (track.setVolume(value) != AudioTrack.SUCCESS) throw RawOutputFailure("Raw audio volume refused")
    }

    /** Flushes and releases the exact AudioTrack, with release attempted even if flushing fails. */
    override fun close() {
        try {
            track.removeOnRoutingChangedListener(routingListener)
            track.flush()
        } finally {
            track.release()
        }
    }

    private companion object {
        const val LOCAL_ROUTE_LATENCY_MS = 60L
        const val UNKNOWN_ROUTE_LATENCY_MS = 300L
    }
}

/** Samples the hardware clock and rejects extrapolation across a mixer underrun. */
internal class RawPcmPresentationClock(
    private val rate: Int,
    private val readTimestamp: (AudioTimestamp) -> Boolean,
) {
    private val timestamp = AudioTimestamp()
    private var nextTimestampNs = Long.MIN_VALUE
    private var hasTimestamp = false
    private var sampledTimestamp = false
    private var previousUnderruns = 0
    private var minimumTimestampNs = Long.MIN_VALUE
    private var underrunMixerHead: Long? = null

    fun frames(
        nowNs: Long,
        underruns: Int,
        mixerHead: Long,
    ): Long? {
        if (underruns != previousUnderruns) {
            previousUnderruns = underruns
            minimumTimestampNs = nowNs
            underrunMixerHead = mixerHead
            hasTimestamp = false
            nextTimestampNs = nowNs
        }
        if (nowNs >= nextTimestampNs) sample(nowNs)
        if (!hasTimestamp) return null
        val elapsed = nowNs - timestamp.nanoTime
        return elapsed.takeIf { it in -NANOS_PER_SECOND..MAX_TIMESTAMP_AGE_NS }?.let {
            (timestamp.framePosition + it * rate / NANOS_PER_SECOND).coerceAtLeast(0L)
        }
    }

    private fun sample(nowNs: Long) {
        val previousFrame = timestamp.framePosition
        val previousTime = timestamp.nanoTime
        val available = runCatching { readTimestamp(timestamp) }.getOrDefault(false)
        // The 32-bit frame counter can wrap. An idle timestamp can refresh time while keeping the last frame.
        val moved = (timestamp.framePosition - previousFrame).toInt() > 0 && timestamp.nanoTime > previousTime
        val afterUnderrun = underrunMixerHead?.let { (timestamp.framePosition - it).toInt() > 0 } ?: true
        val fresh = available && timestamp.nanoTime >= minimumTimestampNs && afterUnderrun
        val advancing = fresh && (!sampledTimestamp || moved)
        val interval = if (sampledTimestamp && advancing) TIMESTAMP_STABLE_NS else TIMESTAMP_WARMUP_NS
        if (advancing) underrunMixerHead = null
        nextTimestampNs = nowNs + interval
        sampledTimestamp = sampledTimestamp || available
        hasTimestamp = advancing
    }

    /** A new route or play start returns to timestamp warmup. */
    fun reset() {
        sampledTimestamp = false
        hasTimestamp = false
        nextTimestampNs = Long.MIN_VALUE
        minimumTimestampNs = Long.MIN_VALUE
        underrunMixerHead = null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val TIMESTAMP_WARMUP_NS = 50_000_000L
        const val TIMESTAMP_STABLE_NS = 10_000_000_000L
        const val MAX_TIMESTAMP_AGE_NS = TIMESTAMP_STABLE_NS + NANOS_PER_SECOND
    }
}
