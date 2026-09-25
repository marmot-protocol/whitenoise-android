package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFramePerformanceReporterTest {
    @Test
    fun frameworkBucketsMapToSharedStagesWithoutDoubleCounting() {
        val sample =
            androidFramePerformanceSample(
                inputNanos = 1_200_000L,
                animationNanos = 2_100_000L,
                layoutNanos = 4_900_000L,
                drawNanos = 5_000_000L,
                syncNanos = 2_000_000L,
                commandIssueNanos = 3_000_000L,
                swapBuffersNanos = 6_800_000L,
            )

        assertEquals(
            AndroidFramePerformanceSample(updateMs = 3L, layoutMs = 4L, drawMs = 10L, presentMs = 6L),
            sample,
        )
    }

    @Test
    fun unsupportedFrameworkBucketsCannotProduceNegativeDurations() {
        val sample =
            androidFramePerformanceSample(
                inputNanos = -1L,
                animationNanos = -1L,
                layoutNanos = -1L,
                drawNanos = -1L,
                syncNanos = -1L,
                commandIssueNanos = -1L,
                swapBuffersNanos = -1L,
            )

        assertEquals(AndroidFramePerformanceSample(0L, 0L, 0L, 0L), sample)
    }
}
