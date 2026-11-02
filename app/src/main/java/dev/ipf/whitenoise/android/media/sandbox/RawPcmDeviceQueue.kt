@file:Suppress("MagicNumber") // PCM sample units and bounded device-progress policy.

package dev.ipf.whitenoise.android.media.sandbox

/** Device priming owns real-frame credits separately from the terminal device-only padding. */
@Suppress("LongParameterList") // Fixed device operations remain under one PCM owner.
internal class RawPcmDeviceQueue(
    private val sampleRate: Int,
    private val frameBytes: Int,
    private val normalFrames: Int,
    private val resizeForPriming: Boolean,
    private val resize: (Int) -> Int,
    private val hardwareFrames: () -> Long,
    private val writePadding: (ByteArray, Int) -> Int,
    private val restart: () -> Unit,
    private val nowMs: () -> Long,
) {
    private var outputFrames = 0L
    private var queuedFrames = 0L
    private var observedHead = 0L
    private var effectiveFrames = normalFrames
    private var lastAdvancedAt = nowMs()
    private var restartedHead: Long? = null
    private var terminalHead = 0L
    private var ending = false
    private var paddingRemaining = 0

    init {
        require(sampleRate in 8_000..96_000 && frameBytes in setOf(2, 4))
        require(normalFrames > 0 && normalFrames.toLong() * frameBytes <= RawPcmCapacity.MAX_BUFFER_BYTES)
    }

    private val silence = ByteArray(16 * frameBytes)

    /**
     * Credits only complete PCM16 media frames accepted by the device; refuses media after EOS.
     */
    fun acceptedOutput(bytes: Int) {
        if (ending) throw RawOutputFailure("Raw audio received media after end of stream")
        require(bytes >= 0 && bytes % frameBytes == 0)
        outputFrames += bytes / frameBytes
        queuedFrames += bytes / frameBytes
    }

    /**
     * Validates a monotone device frame head against accepted media and padding credits.
     * The first observed progress restores normal buffer size; refusal becomes an output failure.
     */
    @Suppress("ThrowsCount") // Distinct monotone-credit and restoration guards retain typed device failures.
    private fun head(): Long {
        val value = hardwareFrames()
        if (value > queuedFrames) throw RawOutputFailure("Raw audio playback head exceeds accepted device frames")
        if (value < observedHead) throw RawOutputFailure("Raw audio playback head moved backwards")
        if (value > observedHead) {
            lastAdvancedAt = nowMs()
            restartedHead = null
        }
        if (value > observedHead && effectiveFrames != normalFrames) {
            val restored = resize(normalFrames)
            if (restored != normalFrames) throw RawOutputFailure("Raw audio buffer restoration refused")
            effectiveFrames = restored
        }
        observedHead = value
        return value
    }

    /**
     * Restores normal device capacity and clears media/padding clocks for a caller-reset device head.
     */
    fun reset() {
        if (resize(normalFrames) != normalFrames) throw RawOutputFailure("Raw audio buffer restoration refused")
        outputFrames = 0
        queuedFrames = 0
        observedHead = 0
        effectiveFrames = normalFrames
        lastAdvancedAt = nowMs()
        restartedHead = null
        ending = false
        terminalHead = 0
        paddingRemaining = 0
    }

    /**
     * Returns accepted media duration in microseconds, excluding terminal silence padding.
     */
    fun writtenPositionUs(): Long = outputFrames * 1_000_000L / sampleRate

    val acceptedDeviceFrames: Long get() = queuedFrames
    val physicalHeadFrames: Long get() = observedHead
    val playedOutputFrames: Long get() = minOf(observedHead, outputFrames)
    val mediaDrained: Boolean get() = observedHead >= outputFrames
    val fullyDrained: Boolean get() = paddingRemaining == 0 && observedHead >= queuedFrames

    /**
     * Returns observed played media duration in microseconds, clamped before padding credits.
     */
    fun positionUs(): Long = playedOutputFrames * 1_000_000L / sampleRate

    /**
     * Refreshes and validates the device head, including buffer restoration after progress.
     */
    fun poll() {
        head()
    }

    /** A tiny unschedulable sample quantum may lead video by at most3ms, never by the full buffer. */
    fun prepareForWait(endOfStream: Boolean): Long {
        val played = head()
        val pending = (queuedFrames - played).coerceAtLeast(0)
        prime(played, pending, endOfStream)
        val threshold = if (resizeForPriming) effectiveFrames else 1
        val toleranceUs = tolerance(pending, threshold)
        if (endOfStream && !ending) {
            terminalHead = played
            ending = true
            // Resize first: actual API30 minimum is16 frames on AOSP; larger vendor requirements refuse above.
            paddingRemaining = if (played < outputFrames) (threshold - pending).coerceAtLeast(0).toInt() else 0
            if (paddingRemaining > 16) throw RawOutputFailure("Raw audio terminal padding exceeds the bounded quantum")
        }
        writeTerminalPadding()
        return toleranceUs
    }

    /**
     * Temporarily shrinks an eligible stalled device queue and restarts once per observed head.
     * Refuses platform minimums larger than the bounded terminal quantum when media cannot fill them.
     */
    private fun prime(
        played: Long,
        pending: Long,
        endOfStream: Boolean,
    ) {
        // Once terminal data started, do not re-enter filling with fewer than the platform minimum left.
        if (ending && played > terminalHead) return
        val canPrime = resizeForPriming && pending > 0 && played < outputFrames
        if (canPrime) {
            val primingNeeded = played == 0L || nowMs() - lastAdvancedAt >= 20 || endOfStream
            if (primingNeeded) {
                val requested = minOf(pending, normalFrames.toLong()).toInt()
                val actual = resize(requested)
                if (actual !in 1..normalFrames) throw RawOutputFailure("Raw audio priming buffer refused")
                effectiveFrames = actual
                if (restartedHead != played) {
                    restart()
                    restartedHead = played
                }
                if (actual > pending && actual > 16) {
                    throw RawOutputFailure("Raw audio minimum priming buffer exceeds the bounded quantum")
                }
            }
        }
    }

    /**
     * Computes the microsecond video lead allowed for a short priming quantum; refuses above 3 ms.
     */
    private fun tolerance(
        pending: Long,
        threshold: Int,
    ): Long {
        val toleranceUs =
            if (pending < threshold && resizeForPriming && threshold <= 16) {
                maxOf(1000L, (threshold + 2L) * 1_000_000L / sampleRate + 1)
            } else {
                1000L
            }
        if (toleranceUs > 3000) throw RawOutputFailure("Raw audio priming tolerance exceeds the bounded quantum")
        return toleranceUs
    }

    /**
     * Credits only frame-aligned silence actually accepted by the device, separately from media time.
     */
    private fun writeTerminalPadding() {
        if (ending && paddingRemaining > 0) {
            val requestedBytes = paddingRemaining * frameBytes
            val accepted = writePadding(silence, requestedBytes)
            if (accepted !in 0..requestedBytes || accepted % frameBytes != 0) {
                throw RawOutputFailure("Raw audio terminal padding refused")
            }
            val frames = accepted / frameBytes
            queuedFrames += frames
            paddingRemaining -= frames
        }
    }
}
