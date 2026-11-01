package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WaveformAccumulatorTest {
    @Test
    fun pcmAndExistingChunkSummaryProduceExactlyTheSameSignal() {
        val samples = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { samples.putShort(8192) }
        repeat(4) { samples.putShort(Short.MIN_VALUE) }
        samples.flip()
        val pcm = WaveformAccumulator()
        samples.limit(8)
        pcm.addPcm16(samples, 8, 1)
        samples.limit(16)
        pcm.addPcm16(samples, 8, 1)
        val summary = WaveformAccumulator()
        summary.addChunk(1.0, 0.25f, 4)
        summary.addChunk(4.0, 1f, 4)
        assertArrayEquals(summary.finish(), pcm.finish(), 0f)
        assertArrayEquals(FloatArray(64) { if (it < 32) 0.525f else 1f }, pcm.finish(), 0.000001f)
    }

    @Test
    fun shortStereoClipFillsEveryBarAndSilenceRemainsUnavailable() {
        val pcm =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Short.MIN_VALUE)
                .putShort(0)
        pcm.flip()
        val signal = WaveformAccumulator()
        signal.addPcm16(pcm, 4, 2)
        assertArrayEquals(FloatArray(64) { 1f }, signal.finish(), 0f)
        val silence = WaveformAccumulator()
        silence.addChunk(0.0, 0f, 10)
        assertNull(silence.finish())
    }

    @Test
    fun partialFramesAndNonFiniteSummariesAreRefused() {
        val signal = WaveformAccumulator()
        assertThrows(IllegalArgumentException::class.java) { signal.addPcm16(ByteBuffer.allocate(4), 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { signal.addChunk(Double.NaN, 0f, 1) }
        assertThrows(IllegalArgumentException::class.java) { signal.addChunk(1.0, Float.POSITIVE_INFINITY, 1) }
    }
}
