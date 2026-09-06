package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AppPerformanceOperationSnapshotFfi
import dev.ipf.marmotkit.DurationHistogramBucketFfi
import dev.ipf.marmotkit.DurationHistogramSnapshotFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pins the local probe's numeric-only reporting across failed native operations. */
class MediaProbePhaseReportTest {
    /** Earlier operations must not enter this run's counts or histogram. */
    @Test
    fun reportsOnlyTheMeasuredIntervalIncludingFailures() {
        val before = operation(attempts = 10uL, successes = 8uL, failures = 2uL, counts = listOf(4uL, 6uL), sum = 24uL)
        val after = operation(attempts = 12uL, successes = 9uL, failures = 3uL, counts = listOf(5uL, 7uL), sum = 30uL)

        assertEquals(
            listOf(
                "phase=host_setup attempts=2 successes=1 failures=1 duration_sum_ms=6 overflow_count=0",
                "phase=host_setup upper_bound_ms=1 count=1",
                "phase=host_setup upper_bound_ms=10 count=1",
            ),
            mediaProbePhaseReport(MediaProbeNativePhase.HOST_SETUP, before, after),
        )
    }

    /** A reset must fail validation instead of wrapping an unsigned count into a huge sample. */
    @Test
    fun rejectsCounterReset() {
        val before = operation()
        val after = before.copy(attempts = before.attempts - 1uL)
        assertThrows(IllegalArgumentException::class.java) {
            mediaProbePhaseReport(MediaProbeNativePhase.DOWNLOAD, before, after)
        }
    }

    /** Histogram schema changes cannot silently drop or mislabel buckets through zip. */
    @Test
    fun rejectsChangedHistogramBounds() {
        val before = operation()
        val changedHistogram = before.durationMs.copy(buckets = listOf(DurationHistogramBucketFfi(20uL, 10uL)))
        val after = before.copy(durationMs = changedHistogram)
        assertThrows(IllegalArgumentException::class.java) {
            mediaProbePhaseReport(MediaProbeNativePhase.DECRYPT, before, after)
        }
    }

    /** A saturated native sum is not an exact interval measurement. */
    @Test
    fun rejectsSaturatedDurationSum() {
        val before = operation()
        val after = before.copy(durationMs = before.durationMs.copy(sumMs = ULong.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) {
            mediaProbePhaseReport(MediaProbeNativePhase.BODY_TRANSFER, before, after)
        }
    }

    /** Creates synthetic public FFI values without starting a native runtime. */
    private fun operation(
        attempts: ULong = 10uL,
        successes: ULong = 8uL,
        failures: ULong = 2uL,
        counts: List<ULong> = listOf(4uL, 6uL),
        sum: ULong = 24uL,
    ) = AppPerformanceOperationSnapshotFfi(
        attempts,
        successes,
        failures,
        DurationHistogramSnapshotFfi(
            listOf(DurationHistogramBucketFfi(1uL, counts[0]), DurationHistogramBucketFfi(10uL, counts[1])),
            0uL,
            sum,
        ),
    )
}
