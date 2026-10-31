package dev.ipf.whitenoise.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil

/** Shared decoded-frame/chunk signal; duration metadata never determines waveform buckets. */
internal class WaveformAccumulator {
    private val chunkFrameStarts = ArrayList<Long>()
    private val chunkFrameCounts = ArrayList<Int>()
    private val chunkSums = ArrayList<Double>()
    private val chunkPeaks = ArrayList<Float>()
    private var totalFrames = 0L

    fun addChunk(
        sum: Double,
        peak: Float,
        frames: Int,
    ) {
        require(frames > 0 && sum.isFinite() && sum >= 0.0 && sum <= frames.toDouble())
        require(peak.isFinite() && peak in 0f..1f && chunkFrameStarts.size < MAX_CHUNKS)
        chunkFrameStarts.add(totalFrames)
        chunkFrameCounts.add(frames)
        chunkSums.add(sum)
        chunkPeaks.add(peak)
        totalFrames = Math.addExact(totalFrames, frames.toLong())
    }

    fun addPcm16(
        input: ByteBuffer,
        byteCount: Int,
        channels: Int,
    ) {
        require(channels in 1..2 && byteCount > 0 && byteCount <= input.remaining())
        require(byteCount % (channels * 2) == 0)
        input.order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var peak = 0f
        val frames = byteCount / (channels * 2)
        repeat(frames) {
            var amplitude = 0f
            repeat(channels) { amplitude += abs(input.short.toInt()) / PCM16_FULL_SCALE }
            val folded = (amplitude / channels).coerceIn(0f, 1f)
            sum += folded
            if (folded > peak) peak = folded
        }
        addChunk(sum, peak, frames)
    }

    @Suppress("ReturnCount") // Empty and silent input return no waveform; successful input returns normalized bars.
    fun finish(): FloatArray? {
        if (totalFrames <= 0L) return null

        // Track BOTH the peak per bucket and the running sum. Peak amplitude
        // produces visually punchier bars than mean-abs (real speech has
        // many quiet samples between syllables; mean averages them down).
        // Each chunk spans a frame range; credit every bar bucket that range
        // overlaps so short clips (fewer chunks than BARS) do not comb.
        val peaks = FloatArray(BARS)
        val sums = DoubleArray(BARS)
        val weights = DoubleArray(BARS)
        for (i in chunkFrameStarts.indices) {
            val chunkStart = chunkFrameStarts[i].toDouble()
            val chunkEnd = (chunkFrameStarts[i] + chunkFrameCounts[i]).coerceAtMost(totalFrames).toDouble()
            if (chunkEnd <= chunkStart) continue
            val firstBucket = ((chunkStart * BARS) / totalFrames).toInt().coerceIn(0, BARS - 1)
            val lastBucket =
                (ceil((chunkEnd * BARS) / totalFrames.toDouble()).toInt() - 1)
                    .coerceIn(0, BARS - 1)
            val chunkMean = chunkSums[i] / chunkFrameCounts[i]
            for (bucket in firstBucket..lastBucket) {
                val bucketFrameStart = bucket.toDouble() * totalFrames / BARS
                val bucketFrameEnd = (bucket + 1).toDouble() * totalFrames / BARS
                val overlapStart = maxOf(chunkStart, bucketFrameStart)
                val overlapEnd = minOf(chunkEnd, bucketFrameEnd)
                val overlapFrames = overlapEnd - overlapStart
                if (overlapFrames <= 0.0) continue
                if (chunkPeaks[i] > peaks[bucket]) peaks[bucket] = chunkPeaks[i]
                sums[bucket] += chunkMean * overlapFrames
                weights[bucket] += overlapFrames
            }
        }

        // Hybrid signal: peak per bucket biased by mean (peak * 0.7 +
        // mean * 0.3) — peaks give visual punch, mean prevents single-spike
        // outliers from flattening neighbours. sqrt() applied at the end
        // perceptually lifts quiet sections so the shape reads as audio
        // dynamics, not raw linear power.
        val hybrid =
            FloatArray(BARS) { i ->
                val mean = if (weights[i] > 0.0) (sums[i] / weights[i]).toFloat() else 0f
                peaks[i] * PEAK_WEIGHT + mean * MEAN_WEIGHT
            }
        val maxV = hybrid.maxOrNull()?.takeIf { it > 0f } ?: return null
        return FloatArray(BARS) { i ->
            val norm = (hybrid[i] / maxV).coerceIn(0f, 1f)
            FLOOR + (1f - FLOOR) * kotlin.math.sqrt(norm)
        }
    }

    private companion object {
        const val BARS = AudioWaveformExtractor.BARS
        const val FLOOR = 0.05f
        const val MAX_CHUNKS = 500_000
        const val PCM16_FULL_SCALE = 32768f
        const val PEAK_WEIGHT = 0.7f
        const val MEAN_WEIGHT = 0.3f
    }
}
