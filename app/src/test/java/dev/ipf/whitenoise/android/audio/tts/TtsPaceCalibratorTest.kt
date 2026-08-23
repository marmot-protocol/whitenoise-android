package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPaceCalibratorTest {
    @Test
    fun convergesTowardTheMeasuredPace() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)

        // A voice speaking 100 units in 3000 ms is 30 ms/unit.
        repeat(20) { calibrator.observe(unitCount = 100, elapsedMs = 3_000, rate = 1.0f) }

        assertEquals(30.0, calibrator.msPerUnitAt1x, 0.5)
    }

    @Test
    fun rateIsNormalizedToOneX() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)

        // 100 units in 1500 ms at 2x is 30 ms/unit at 1x.
        repeat(20) { calibrator.observe(unitCount = 100, elapsedMs = 1_500, rate = 2.0f) }

        assertEquals(30.0, calibrator.msPerUnitAt1x, 0.5)
    }

    @Test
    fun shortSamplesAreRejected() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)

        assertFalse(calibrator.observe(unitCount = 30, elapsedMs = 3_000, rate = 1.0f))
        assertFalse(calibrator.observe(unitCount = 100, elapsedMs = 200, rate = 1.0f))
        assertEquals(17.5, calibrator.msPerUnitAt1x, 0.0)
    }

    @Test
    fun implausibleMeasurementsAreCorruptedSamplesNotDiscoveries() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)

        // 100 units in 60 s: something stalled; no plausible voice is that slow.
        assertFalse(calibrator.observe(unitCount = 100, elapsedMs = 60_000, rate = 1.0f))
        // 1000 units in 500 ms: no plausible voice is that fast either.
        assertFalse(calibrator.observe(unitCount = 1_000, elapsedMs = 500, rate = 1.0f))
        assertEquals(17.5, calibrator.msPerUnitAt1x, 0.0)
    }

    @Test
    fun singleSampleMovesTheEstimateOnlyPartWay() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)

        assertTrue(calibrator.observe(unitCount = 100, elapsedMs = 3_000, rate = 1.0f))

        assertTrue(calibrator.msPerUnitAt1x > 17.5)
        assertTrue(calibrator.msPerUnitAt1x < 30.0)
    }

    @Test
    fun resetForgetsWhatTheOldEngineTaught() {
        val calibrator = TtsPaceCalibrator(initialMsPerUnitAt1x = 17.5)
        repeat(20) { calibrator.observe(unitCount = 100, elapsedMs = 3_000, rate = 1.0f) }

        calibrator.reset()

        assertEquals(TtsWordTimingEstimate.DEFAULT_MS_PER_UNIT_AT_1X, calibrator.msPerUnitAt1x, 0.0)
    }

    @Test
    fun initialValueIsClampedToThePlausibleBand() {
        assertEquals(9.0, TtsPaceCalibrator(initialMsPerUnitAt1x = 1.0).msPerUnitAt1x, 0.0)
        assertEquals(40.0, TtsPaceCalibrator(initialMsPerUnitAt1x = 400.0).msPerUnitAt1x, 0.0)
    }
}
