@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

/** Sample-index clipping preserves the part of a decoded PCM buffer after an exact seek target. */
internal object RawAvTiming {
    data class PcmSlice(
        val offset: Int,
        val byteCount: Int,
        val timestampUs: Long,
    )

    fun clipPcm(
        byteCount: Int,
        rate: Int,
        channels: Int,
        timestampUs: Long,
        targetUs: Long,
    ): PcmSlice {
        require(rate in 8000..96000 && channels in 1..2)
        val frameBytes = channels * 2
        require(byteCount in 0..AvStreamProtocol.PCM_BYTES && byteCount % frameBytes == 0)
        require(timestampUs in -10_000_000L..AvStreamProtocol.MAX_DURATION_US)
        require(targetUs in 0..AvStreamProtocol.MAX_DURATION_US)
        val totalFrames = byteCount / frameBytes
        val delta = (targetUs - timestampUs).coerceAtLeast(0)
        val skippedFrames = ((delta * rate + 999_999) / 1_000_000).coerceAtMost(totalFrames.toLong()).toInt()
        return PcmSlice(
            skippedFrames * frameBytes,
            (totalFrames - skippedFrames) * frameBytes,
            timestampUs + skippedFrames.toLong() * 1_000_000 / rate,
        )
    }

    fun pcmEnd(
        timestampUs: Long,
        bytes: Int,
        rate: Int,
        channels: Int,
    ): Long = timestampUs + bytes.toLong() / (channels * 2) * 1_000_000 / rate

    fun wallPosition(
        anchorUs: Long,
        startedMs: Long,
        nowMs: Long,
        speed: Float,
    ): Long {
        require(speed.isFinite() && speed in 0.5f..RawPcmCapacity.MAX_SPEED)
        val elapsed = (nowMs - startedMs).coerceAtLeast(0).toDouble()
        val delta = (elapsed * 1000 * speed).coerceAtMost(AvStreamProtocol.MAX_DURATION_US.toDouble()).toLong()
        return (anchorUs + delta).coerceIn(0, AvStreamProtocol.MAX_DURATION_US)
    }

    fun videoIsDue(
        timestampUs: Long,
        clockUs: Long,
    ): Boolean = timestampUs <= clockUs + 1000
}

/** Activation changes retire an old source run; seek changes revoke only its pending outputs. */
internal data class RawAvGeneration(
    val activation: Long,
    val seek: Long,
) {
    fun accepts(
        current: RawAvGeneration,
        alive: Boolean,
        playing: Boolean,
        includeSeek: Boolean = true,
    ): Boolean = alive && playing && activation == current.activation && (!includeSeek || seek == current.seek)
}

/** The automatic poster is valid only for this still-paused source activation and seek. */
internal data class RawAvPosterTarget(
    val generation: RawAvGeneration,
    val positionMs: Long,
) {
    fun matches(
        current: RawAvGeneration,
        alive: Boolean,
        playing: Boolean,
        currentPositionMs: Long = positionMs,
    ): Boolean = alive && !playing && generation == current && positionMs == currentPositionMs

    companion object {
        fun positionUs(
            requestedMs: Long,
            durationUs: Long,
        ): Long {
            val requested = requestedMs.coerceIn(0, AvStreamProtocol.MAX_DURATION_US / 1000) * 1000
            return if (durationUs > 0) requested.coerceAtMost(durationUs) else requested
        }
    }
}
