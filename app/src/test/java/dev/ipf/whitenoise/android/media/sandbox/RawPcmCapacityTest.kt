package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPcmCapacityTest {
    @Test
    fun platformMinimumDominatingQuarterSecondStillSupportsMaximumSpeed() {
        val capacity = RawPcmCapacity.bytes(100_000, 8_000, 2)
        assertEquals(200_004, capacity)
        assertTrue(capacity > 100_000 * RawPcmCapacity.MAX_SPEED)
    }

    @Test
    fun quarterSecondRemainsTheFloorWhenThePlatformMinimumIsSmall() {
        assertEquals(96_000, RawPcmCapacity.bytes(1000, 96_000, 2))
    }

    @Test
    fun minimumPlusSpeedMarginRoundsUpToWholeInterleavedFrames() {
        assertEquals(200_012, RawPcmCapacity.bytes(100_003, 8_000, 2))
        assertEquals(200_008, RawPcmCapacity.bytes(100_003, 8_000, 1))
        assertEquals(0, RawPcmCapacity.bytes(100_003, 8_001, 1) % 2)
        assertEquals(0, RawPcmCapacity.bytes(100_003, 8_001, 2) % 4)
    }

    @Test
    fun capacityIsRefusedRatherThanClampedBelowTheMaximumSpeedContract() {
        assertEquals(RawPcmCapacity.MAX_BUFFER_BYTES, RawPcmCapacity.bytes(524_286, 8_000, 2))
        assertTrue(runCatching { RawPcmCapacity.bytes(524_288, 8_000, 2) }.isFailure)
        assertTrue(runCatching { RawPcmCapacity.bytes(Int.MAX_VALUE, 96_000, 2) }.isFailure)
        assertTrue(runCatching { RawPcmCapacity.bytes(-1, 8_000, 2) }.isFailure)
    }
}
