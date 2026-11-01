package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the real pinned Media3 processor. It does not construct or simulate an AudioTrack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RawSonicSegmentTest {
    @Test
    fun tinyStreamsFlushRealNonzeroOutputAtEveryOfferedRate() {
        val expectedFrames = mapOf(0.25f to 8L, 0.5f to 4L, 1f to 2L, 2f to 1L)
        for ((speed, expected) in expectedFrames) {
            RawSonicSegment(8_000, 1, speed).use { segment ->
                val pcm = samples(12_000, 12_000)
                assertEquals(pcm.size, segment.queueInput(pcm, 0, pcm.size))
                val values = drain(segment).toMutableList()
                segment.endOfInput()
                values += drain(segment)
                assertTrue("speed=$speed", segment.ended)
                assertEquals("speed=$speed", expected, segment.exposedOutputFrames)
                assertEquals(expected.toInt(), values.size)
                assertTrue("speed=$speed must produce real nonzero samples", values.any { it.toInt() != 0 })
                assertEquals(2L, segment.acceptedInputFrames)
                assertEquals(250L, segment.mediaDurationUs(Long.MAX_VALUE / 4))
            }
        }
    }

    @Test
    fun partialOutputRetainsItsExactOwnedViewAndBlocksInputAndEos() {
        RawSonicSegment(8_000, 1, 1f).use { segment ->
            val pcm = samples(1, 2, 3, 4)
            segment.queueInput(pcm, 0, pcm.size)
            val output = segment.output()
            assertTrue(output.isReadOnly)
            assertEquals(1, output.short.toInt())
            assertSame(output, segment.output())
            assertThrows(IllegalStateException::class.java) { segment.queueInput(pcm, 0, pcm.size) }
            assertThrows(IllegalStateException::class.java) { segment.endOfInput() }
            assertEquals(listOf<Short>(2, 3, 4), drain(segment))
            segment.endOfInput()
            assertTrue(segment.ended)
        }
    }

    @Test
    fun eosIsIdempotentButNeverDiscardsPendingTail() {
        RawSonicSegment(8_000, 1, 0.25f).use { segment ->
            val pcm = samples(10_000, 10_000)
            segment.queueInput(pcm, 0, pcm.size)
            assertFalse(segment.output().hasRemaining())
            segment.endOfInput()
            val tail = segment.output()
            assertTrue(tail.hasRemaining())
            assertFalse(segment.ended)
            segment.endOfInput()
            assertSame(tail, segment.output())
            assertEquals(8, drain(segment).size)
            assertTrue(segment.ended)
            assertThrows(IllegalStateException::class.java) { segment.queueInput(pcm, 0, pcm.size) }
        }
    }

    @Test
    fun realProcessedOutputMatchesWholeAndPartialReads() {
        val pcm = samples(12_000, 12_000)
        for (speed in listOf(0.25f, 0.5f, 2f)) {
            RawSonicSegment(8_000, 1, speed).use { whole ->
                RawSonicSegment(8_000, 1, speed).use { partial ->
                    whole.queueInput(pcm, 0, pcm.size)
                    partial.queueInput(pcm, 0, pcm.size)
                    whole.endOfInput()
                    partial.endOfInput()
                    val expected = drain(whole)
                    val actual = drainPartially(partial)
                    assertEquals(expected, actual)
                    assertTrue(partial.ended)
                }
            }
        }
    }

    private fun drainPartially(segment: RawSonicSegment): List<Short> {
        val actual = mutableListOf<Short>()
        val output = segment.output()
        while (output.hasRemaining()) {
            actual += output.short
            if (output.hasRemaining()) assertSame(output, segment.output())
        }
        return actual
    }

    @Test
    fun rateAndInputGuardsRejectBeforeGrowingTheProcessor() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.125f, 2.01f)) {
            assertThrows(IllegalArgumentException::class.java) { RawSonicSegment(8_000, 1, speed) }
        }
        assertThrows(IllegalArgumentException::class.java) { RawSonicSegment(7_999, 1, 1f) }
        assertThrows(IllegalArgumentException::class.java) { RawSonicSegment(96_001, 1, 1f) }
        assertThrows(IllegalArgumentException::class.java) { RawSonicSegment(8_000, 3, 1f) }
        RawSonicSegment(8_000, 2, 0.25f).use { segment ->
            assertThrows(IllegalArgumentException::class.java) { segment.queueInput(ByteArray(324), 0, 324) }
            assertThrows(IllegalArgumentException::class.java) { segment.queueInput(ByteArray(4), 0, 3) }
            assertThrows(IllegalArgumentException::class.java) { segment.queueInput(ByteArray(4), 2, 4) }
            assertEquals(0L, segment.acceptedInputFrames)
            assertEquals(0L, segment.exposedOutputFrames)
        }
    }

    @Test
    fun maximumRateStereoUsesBoundedQuantaAndClampedSourceMapping() {
        RawSonicSegment(96_000, 2, 0.25f).use { segment ->
            val pcm = ByteArray(960 * 4)
            repeat(3) {
                assertEquals(pcm.size, segment.queueInput(pcm, 0, pcm.size))
                drain(segment)
                assertTrue(segment.mediaDurationUs(1_000_000) <= segment.acceptedInputFrames * 1_000_000 / 96_000)
            }
            segment.endOfInput()
            drain(segment)
            assertTrue(segment.ended)
            assertEquals(2_880L, segment.acceptedInputFrames)
            assertEquals(30_000L, segment.mediaDurationUs(1_000_000))
            assertEquals(0L, segment.mediaDurationUs(0))
            assertThrows(IllegalArgumentException::class.java) { segment.mediaDurationUs(-1) }
        }
    }

    @Test
    fun unityBypassesInactiveProcessorWithoutChangingSamplesOrClock() {
        RawSonicSegment(44_100, 1, 1f).use { segment ->
            val pcm = samples(Short.MIN_VALUE.toInt(), -1, 0, 1, Short.MAX_VALUE.toInt())
            segment.queueInput(pcm, 0, pcm.size)
            val expected = listOf(Short.MIN_VALUE, (-1).toShort(), 0.toShort(), 1.toShort(), Short.MAX_VALUE)
            assertEquals(expected, drain(segment))
            assertEquals(100L, segment.mediaDurationUs(100))
            segment.endOfInput()
            assertTrue(segment.ended)
            assertEquals(5L, segment.exposedOutputFrames)
        }
    }

    @Test
    fun closedSegmentsCannotBeReusedAndChangedOutputLimitsAreRejected() {
        val segment = RawSonicSegment(8_000, 1, 1f)
        val pcm = samples(1, 2)
        segment.queueInput(pcm, 0, pcm.size)
        segment.output().limit(0)
        assertThrows(IllegalStateException::class.java) { segment.output() }
        segment.close()
        segment.close()
        assertThrows(IllegalStateException::class.java) { segment.queueInput(pcm, 0, pcm.size) }
        assertThrows(IllegalStateException::class.java) { segment.output() }
        assertThrows(IllegalStateException::class.java) { segment.endOfInput() }
    }

    private fun samples(vararg values: Int): ByteArray =
        ByteBuffer
            .allocate(values.size * 2)
            .order(ByteOrder.nativeOrder())
            .apply {
                values.forEach { putShort(it.toShort()) }
            }.array()

    private fun drain(segment: RawSonicSegment): List<Short> {
        val samples = mutableListOf<Short>()
        val output = segment.output()
        while (output.hasRemaining()) samples += output.short
        return samples
    }
}
