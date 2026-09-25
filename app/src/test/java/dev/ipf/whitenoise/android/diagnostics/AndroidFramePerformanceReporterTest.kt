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
    fun unsupportedFrameworkBucketsRemainUnavailable() {
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

        assertEquals(AndroidFramePerformanceSample(null, null, null, null), sample)
    }

    @Test
    fun incompleteCombinedStageIsUnavailableWithoutDiscardingCompleteStages() {
        val sample =
            androidFramePerformanceSample(
                inputNanos = 2_000_000L,
                animationNanos = -1L,
                layoutNanos = 3_000_000L,
                drawNanos = 4_000_000L,
                syncNanos = -1L,
                commandIssueNanos = 1_000_000L,
                swapBuffersNanos = 5_000_000L,
            )

        assertEquals(AndroidFramePerformanceSample(null, 3L, null, 5L), sample)
    }

    @Test
    fun queuedCallbacksStayBoundToTheirLifetimeAndFirstOwner() {
        var currentOwner: String? = null
        val observed = mutableListOf<String>()
        val guard =
            FramePerformanceCallbackGuard(
                captureOwner = { currentOwner },
                isCurrent = { it == currentOwner },
            )

        assertEquals(false, guard.runIfCurrent { observed += it })
        currentOwner = "first"
        assertEquals(true, guard.runIfCurrent { observed += it })
        currentOwner = "replacement"
        assertEquals(false, guard.runIfCurrent { observed += it })
        guard.invalidate()
        currentOwner = "first"
        assertEquals(false, guard.runIfCurrent { observed += it })

        assertEquals(listOf("first"), observed)
    }
}
