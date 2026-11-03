@file:Suppress("MagicNumber") // PCM frame clocks, unity hardware rate and bounded streaming policy.

package dev.ipf.whitenoise.android.media.sandbox

import android.media.AudioAttributes
import android.os.SystemClock

/** One unity AudioTrack and one flushable fixed-rate raw-PCM segment; no compressed parser enters the parent. */
@Suppress("TooManyFunctions") // Device ownership, DSP transitions and their scalar clocks retire together.
internal class RawPcmOutput(
    override val sampleRate: Int,
    override val channels: Int,
    private val device: RawPcmDevice,
) : RawPcmSink {
    constructor(rate: Int, channels: Int, attributes: AudioAttributes) : this(
        rate,
        channels,
        AndroidRawPcmDevice(rate, channels, attributes),
    )

    private val frameBytes = channels * Short.SIZE_BYTES
    private val queue: RawPcmDeviceQueue
    private lateinit var segment: RawSonicSegment
    private var requestedSpeed = 1f
    private var segmentEnding = false
    private var inputEnded = false
    private var sourceBaseFrames = 0L
    private val segmentSourceUs: Long get() = framesToUs(sourceBaseFrames)
    private var physicalBase = 0L
    private var lastHead = 0L
    private var headWrap = 0L
    private var lastHardwareFrames = 0L
    private var stalledSinceMs: Long? = null
    private var position = 0L
    private var buffered = 0L
    private var written = 0L
    private var finished = false
    private var playing = false
    private var closed = false
    private var fault: RawOutputFailure? = null

    init {
        @Suppress("TooGenericExceptionCaught") // Any device/DSP construction failure closes its exact owned device.
        try {
            require(sampleRate in 8_000..96_000 && channels in 1..2)
            val normal = device.normalFrames
            checkCapacity()
            if (normal <= 0 || normal > device.capacityFrames) throw RawOutputFailure("Raw audio buffer refused")
            checkThreshold()
            segment = RawSonicSegment(sampleRate, channels, 1f)
            queue =
                RawPcmDeviceQueue(
                    sampleRate,
                    frameBytes,
                    normal,
                    device.legacyPriming,
                    resize = { device.resize(it) },
                    hardwareFrames = { hardwareFrames() - physicalBase },
                    writePadding = { bytes, count -> device.writeSilence(bytes, count) },
                    restart = {
                        if (playing && !closed) {
                            device.pause()
                            device.play()
                        }
                    },
                    nowMs = SystemClock::elapsedRealtime,
                )
        } catch (failure: Exception) {
            runCatching { device.close() }
            if (::segment.isInitialized) segment.close()
            throw RawOutputFailure("Raw audio initialization refused", failure)
        } catch (failure: OutOfMemoryError) {
            runCatching { device.close() }
            if (::segment.isInitialized) segment.close()
            throw failure
        }
    }

    /**
     * Accepts whole interleaved PCM frames in bounded quanta; zero means the current segment must drain
     * first.
     */
    @Synchronized override fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int = write(bytes, offset, bytes.size - offset)

    /**
     * Accepts whole interleaved PCM frames in bounded quanta; zero means the current segment must drain
     * first.
     */
    @Synchronized override fun write(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int =
        checked {
            require(bytes.size <= MAX_CHUNK_BYTES && offset >= 0 && byteCount >= 0 && offset <= bytes.size - byteCount)
            require(offset % frameBytes == 0 && byteCount % frameBytes == 0)
            check(!inputEnded)
            pump()
            if (segmentEnding || segment.speed != requestedSpeed || segment.output().hasRemaining()) return@checked 0
            val count = minOf(byteCount, (sampleRate / 100) * frameBytes)
            val consumed = segment.queueInput(bytes, offset, count)
            written = framesToUs(sourceBaseFrames + segment.acceptedInputFrames)
            pump()
            consumed
        }

    /**
     * Flushes a declared input tail and pumps queued output before returning the renderer polling tolerance.
     */
    @Synchronized override fun prepareForWait(endOfStream: Boolean): Long =
        checked {
            if (endOfStream) inputEnded = true
            pump()
        }

    /** Main/voice snapshots never read hardware, restore buffers, allocate DSP output or throw deferred faults. */
    @Synchronized override fun positionUs(): Long = position

    @Synchronized override fun writtenPositionUs(): Long = written

    @Synchronized override fun bufferedPositionUs(): Long = buffered

    @Synchronized override fun drained(): Boolean {
        fault?.let { throw it }
        return closed || finished
    }

    /**
     * Records the latest supported rate without resuming playback; the renderer drains the old segment before
     * adopting it.
     */
    @Synchronized override fun speed(value: Float) {
        require(value.isFinite() && value in RawPcmCapacity.MIN_SPEED..RawPcmCapacity.MAX_SPEED)
        if (!closed) requestedSpeed = value // One latest scalar; the renderer drains the old segment off Main.
    }

    /** Starts the existing unity-rate device and clears its stalled-output deadline. */
    @Synchronized override fun play() =
        checked {
            device.play()
            playing = true
            stalledSinceMs = null
        }

    /** Pauses the exact device; a hardware refusal is retained as the original typed output fault. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Preserve the typed deferred output cause.
    @Synchronized
    override fun pause() {
        playing = false
        if (!closed) {
            try {
                device.pause()
            } catch (failure: Exception) {
                remember(failure)
                throw checkNotNull(fault)
            }
        }
    }

    /** Applies a finite unit-range gain through the serialized device boundary. */
    @Synchronized override fun volume(value: Float) =
        checked {
            require(value.isFinite() && value in 0f..1f)
            device.volume(value)
        }

    /**
     * Refuses route capacities outside the byte budget rather than silently shrinking the promised speed
     * margin.
     */
    private fun checkCapacity() {
        val capacity = device.capacityFrames
        if (capacity <= 0 || capacity.toLong() * frameBytes > RawPcmCapacity.MAX_BUFFER_BYTES) {
            throw RawOutputFailure("Raw audio route buffer exceeds the bounded capacity")
        }
    }

    /** Requires the device to acknowledge the single-frame start threshold used by the bounded queue. */
    private fun checkThreshold() {
        if (!device.startThresholdOne()) {
            throw RawOutputFailure("Raw audio start threshold refused")
        }
    }

    /** Serialized renderer boundary; only this path mutates priming or retrieves reusable DSP buffers. */
    @Suppress("CyclomaticComplexMethod") // One serialized transition owns output credits, EOS and rate anchors.
    private fun pump(): Long {
        if (!playing) return 1000L
        checkCapacity()
        checkThreshold()
        queue.poll()
        var output = segment.output()
        if (output.hasRemaining()) {
            val before = output.position()
            val requested = output.remaining()
            val count = device.write(output, requested)
            if (count !in 0..requested || count % frameBytes != 0 || output.position() != before + count) {
                throw RawOutputFailure("Raw processed audio write refused")
            }
            queue.acceptedOutput(count)
        }
        output = segment.output()
        val segmentReady = !output.hasRemaining() && !segmentEnding
        if (segmentReady && (inputEnded || segment.speed != requestedSpeed)) {
            segment.endOfInput()
            segmentEnding = true
            output = segment.output()
        }
        val terminal = segmentEnding && segment.ended && !output.hasRemaining()
        val tolerance = queue.prepareForWait(terminal)
        queue.poll()
        val playedOutputUs = framesToUs(queue.playedOutputFrames)
        position = maxOf(position, segmentSourceUs + segment.mediaDurationUs(playedOutputUs)).coerceAtMost(written)
        val queuedMediaUs = segmentSourceUs + segment.mediaDurationUs(queue.writtenPositionUs())
        buffered = maxOf(position, queuedMediaUs).coerceAtMost(written)
        val physical = physicalBase + queue.acceptedDeviceFrames
        val now = SystemClock.elapsedRealtime()
        val head = physicalBase + queue.physicalHeadFrames
        val pendingDevice = output.hasRemaining() || head < physical
        if (head > lastHardwareFrames || !pendingDevice) {
            stalledSinceMs = null
        } else {
            val since = stalledSinceMs
            if (since == null) {
                stalledSinceMs = now
            } else if (now - since >= 5000) {
                throw RawOutputFailure("Raw audio device made no progress")
            }
        }
        lastHardwareFrames = maxOf(lastHardwareFrames, head)
        if (terminal && queue.mediaDrained && inputEnded) {
            position = written
            buffered = written
            finished = true
        } else if (terminal && queue.fullyDrained && !inputEnded) {
            completeRateSegment()
        }
        return tolerance
    }

    /**
     * Carries source and hardware frame anchors across a drained rate change without replacing the device.
     */
    private fun completeRateSegment() {
        sourceBaseFrames += segment.acceptedInputFrames
        val endpoint = framesToUs(sourceBaseFrames)
        physicalBase += queue.acceptedDeviceFrames
        queue.reset()
        segment.close()
        segment = RawSonicSegment(sampleRate, channels, requestedSpeed)
        position = maxOf(position, endpoint)
        buffered = endpoint
        segmentEnding = false
    }

    /** Captures hardware diagnostics under this sink's existing device ownership monitor. */
    @Synchronized fun observeAudio(
        observation: RawAvPlaybackObservation,
        activation: Long,
        seek: Long,
    ) {
        if (!closed) {
            observation.audio(
                activation,
                seek,
                System.identityHashCode(this),
                SystemClock.elapsedRealtimeNanos(),
                hardwareFrames(),
                device.underruns,
            )
        }
    }

    /** Extends the unsigned 32-bit playback head across wraparound before source-clock conversion. */
    private fun hardwareFrames(): Long {
        val head = device.headPosition().toLong() and 0xffff_ffffL
        if (head < lastHead) headWrap += 1L shl 32
        lastHead = head
        return headWrap + head
    }

    /**
     * Converts source-frame counts with the fixed sample rate; channel interleaving does not change elapsed
     * time.
     */
    private fun framesToUs(frames: Long): Long = frames * 1_000_000L / sampleRate

    /**
     * Retains the first typed output cause so later cleanup failures cannot replace the failure reported to
     * callers.
     */
    private fun remember(failure: Exception) {
        if (fault == null) {
            fault = failure as? RawOutputFailure ?: RawOutputFailure("Raw audio processing refused", failure)
        }
    }

    /** Replays the retained output fault before refusing a closed sink. */
    private fun checkActive() {
        fault?.let { throw it }
        if (closed) throw RawOutputFailure("Raw audio output retired")
    }

    /** Converts device/DSP failures into one retained output fault while preserving the original cause. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Store the original cause in the typed fault.
    private inline fun <T> checked(operation: () -> T): T {
        checkActive()
        return try {
            operation()
        } catch (failure: Exception) {
            remember(failure)
            throw checkNotNull(fault)
        }
    }

    /**
     * Retires the segment and exact device idempotently, attempting every owned cleanup even after a refusal.
     */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        playing = false
        runCatching { segment.close() }
        runCatching { device.pause() }
        runCatching { device.close() }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 256 * 1024
    }
}
