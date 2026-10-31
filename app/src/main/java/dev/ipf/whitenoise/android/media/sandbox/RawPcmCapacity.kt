@file:Suppress("MagicNumber") // Fixed PCM units and one bounded streaming buffer policy.

package dev.ipf.whitenoise.android.media.sandbox

/** Streaming AudioTrack needs strictly more than speed times its platform minimum at speeds above1. */
internal object RawPcmCapacity {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 2f
    const val MAX_BUFFER_BYTES = 1024 * 1024

    fun bytes(
        minimumBytes: Int,
        sampleRate: Int,
        channels: Int,
    ): Int {
        require(minimumBytes > 0)
        require(sampleRate in 8_000..96_000 && channels in 1..2)
        val frameBytes = channels * Short.SIZE_BYTES
        val speedMinimum = kotlin.math.ceil(minimumBytes.toDouble() * MAX_SPEED).toLong() + frameBytes
        val quarterSecond = sampleRate.toLong() * frameBytes / 4
        val required = maxOf(speedMinimum, quarterSecond)
        val aligned = (required + frameBytes - 1) / frameBytes * frameBytes
        require(aligned <= MAX_BUFFER_BYTES) { "Raw audio buffer requirement exceeds the bounded capacity" }
        return aligned.toInt()
    }
}
