package dev.ipf.whitenoise.android.media.sandbox

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed-rate PCM segment. The caller owns hardware writes and source/played-output checkpoints.
 * One owner keeps input, output, EOS and duration guards together.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Suppress("TooManyFunctions")
internal class RawSonicSegment(
    private val sampleRate: Int,
    private val channels: Int,
    val speed: Float,
) : AutoCloseable {
    private val frameBytes: Int
    private val maxInputBytes: Int
    private val input: ByteBuffer
    private val processor: SonicAudioProcessor?
    private var pending: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var pendingLimit = 0
    private var inputEnded = false
    private var closed = false

    var acceptedInputFrames = 0L
        private set
    var exposedOutputFrames = 0L
        private set

    init {
        // These guards precede configure/flush/allocation and underpin the pinned Sonic allocation bound.
        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE)
        require(channels in 1..2)
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED)
        frameBytes = channels * PCM16_BYTES
        maxInputBytes = (sampleRate / INPUT_QUANTA_PER_SECOND) * frameBytes
        input = ByteBuffer.allocateDirect(maxInputBytes).order(ByteOrder.nativeOrder())
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed)
        sonic.setPitch(1f)
        sonic.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        // Public Sonic is also inactive for rates within its documented default-parameter tolerance.
        processor = if (sonic.isActive) sonic.also { it.flush(AudioProcessor.StreamMetadata.DEFAULT) } else null
    }

    /**
     * Consumes one bounded, frame-aligned PCM16 byte range after all prior output is drained.
     * Returns bytes accepted; callers must consume the returned output view before more input or EOS.
     */
    fun queueInput(
        bytes: ByteArray,
        offset: Int,
        byteCount: Int,
    ): Int {
        checkOpen()
        check(!inputEnded)
        require(offset >= 0 && byteCount >= 0 && offset <= bytes.size - byteCount)
        require(byteCount <= maxInputBytes && byteCount % frameBytes == 0)
        requireOutputDrained()
        if (byteCount == 0) return 0
        val newFrames = Math.addExact(acceptedInputFrames, (byteCount / frameBytes).toLong())
        input.clear()
        input.put(bytes, offset, byteCount)
        input.flip()
        if (processor == null) {
            ownOutput(input)
        } else {
            processor.queueInput(input)
            check(!input.hasRemaining())
            captureOutput()
        }
        acceptedInputFrames = newFrames
        return byteCount
    }

    /** The returned view remains owned by this segment until its position reaches its original limit. */
    fun output(): ByteBuffer {
        checkOpen()
        checkPendingShape()
        if (!pending.hasRemaining()) captureOutput()
        return pending
    }

    /**
     * Signals EOS once after draining exposed output, then exposes any processor tail for consumption.
     */
    fun endOfInput() {
        checkOpen()
        if (inputEnded) return
        requireOutputDrained()
        processor?.queueEndOfStream()
        inputEnded = true
        captureOutput()
    }

    val ended: Boolean
        get() {
            checkOpen()
            checkPendingShape()
            return inputEnded && !pending.hasRemaining() && (processor?.isEnded ?: true)
        }

    /** Exposed DSP output is not a hardware clock; clamp rounding to the accepted source duration. */
    fun mediaDurationUs(playoutDurationUs: Long): Long {
        checkOpen()
        require(playoutDurationUs >= 0)
        val sourceDuration = framesToUs(acceptedInputFrames)
        val exposedDuration = framesToUs(exposedOutputFrames)
        val boundedPlayout = playoutDurationUs.coerceAtMost(exposedDuration)
        if (ended && playoutDurationUs >= exposedDuration) return sourceDuration
        val mapped = processor?.getMediaDuration(boundedPlayout) ?: boundedPlayout
        return mapped.coerceIn(0L, sourceDuration)
    }

    /**
     * Drops the pending output reference and resets the processor once; subsequent segment operations fail.
     */
    override fun close() {
        if (closed) return
        closed = true
        pending = AudioProcessor.EMPTY_BUFFER
        pendingLimit = 0
        processor?.reset()
    }

    /**
     * Rejects operations after processor ownership has been released.
     */
    private fun checkOpen() = check(!closed)

    /**
     * Rejects a consumer that changed the segment-owned output limit rather than advancing its position.
     */
    private fun checkPendingShape() {
        check(pending.limit() == pendingLimit) { "PCM output limit changed" }
    }

    /**
     * Requires complete consumption of the current immutable-limit output before replacing it.
     */
    private fun requireOutputDrained() {
        checkPendingShape()
        check(!pending.hasRemaining()) { "PCM output must be consumed before input or EOS" }
    }

    /**
     * Transfers the next processor output view only after the previous view has been drained.
     */
    private fun captureOutput() {
        requireOutputDrained()
        val next = processor?.getOutput() ?: AudioProcessor.EMPTY_BUFFER
        if (next.hasRemaining()) ownOutput(next)
    }

    /**
     * Records bounded whole-frame output and exposes a read-only view whose position the consumer advances.
     * The pre-call input policy bounds processor allocation; this post-call check is supplemental.
     */
    private fun ownOutput(buffer: ByteBuffer) {
        // Supplemental invariant only: pre-call admission, not this check, bounds library allocations.
        check(buffer.remaining() <= MAX_EXPOSED_BYTES && buffer.remaining() % frameBytes == 0)
        exposedOutputFrames = Math.addExact(exposedOutputFrames, (buffer.remaining() / frameBytes).toLong())
        pending = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        pendingLimit = pending.limit()
    }

    /**
     * Converts frames at this segment rate to microseconds with saturation instead of overflow.
     */
    private fun framesToUs(frames: Long): Long {
        val seconds = frames / sampleRate
        if (seconds > Long.MAX_VALUE / MICROS_PER_SECOND) return Long.MAX_VALUE
        val whole = seconds * MICROS_PER_SECOND
        val fraction = (frames % sampleRate) * MICROS_PER_SECOND / sampleRate
        return if (whole > Long.MAX_VALUE - fraction) Long.MAX_VALUE else whole + fraction
    }

    private companion object {
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 96_000
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 2f
        const val PCM16_BYTES = 2
        const val INPUT_QUANTA_PER_SECOND = 100
        const val MICROS_PER_SECOND = 1_000_000L
        const val MAX_EXPOSED_BYTES = 177_120
    }
}
