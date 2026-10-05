package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMembershipTimingsTest {
    @Test
    fun pendingAndProjectionFramesHaveSeparateOneShotTimelines() {
        var now = 100L
        val phases = mutableListOf<Triple<PerformanceOperation, PerformancePhase, Long>>()
        val timings =
            GroupMembershipTimings(
                begin = { operation ->
                    PerformanceTrace(
                        operation,
                        sessionGeneration = 1L,
                        operationId = 1L,
                        startedAtMs = now,
                    )
                },
                nowMs = { now },
                record = { trace, phase, elapsed -> phases += Triple(trace.operation, phase, elapsed) },
            )
        timings.pendingAccepted("local-request")
        now = 105L
        timings.pendingFrame("local-request")
        timings.pendingFrame("local-request")
        now = 110L
        timings.projectionArrived("native-row")
        now = 125L
        timings.projectionFrame("native-row")
        timings.projectionFrame("native-row")
        assertEquals(
            listOf(
                Triple(PerformanceOperation.GROUP_MEMBERSHIP_PENDING, PerformancePhase.ACCEPTED, 0L),
                Triple(PerformanceOperation.GROUP_MEMBERSHIP_PENDING, PerformancePhase.FIRST_LOCAL_FRAME, 5L),
                Triple(
                    PerformanceOperation.GROUP_MEMBERSHIP_PROJECTION,
                    PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED,
                    0L,
                ),
                Triple(PerformanceOperation.GROUP_MEMBERSHIP_PROJECTION, PerformancePhase.FIRST_LOCAL_FRAME, 15L),
            ),
            phases,
        )
    }

    @Test
    fun staleSettlementCannotDiscardNewPendingFrameTiming() {
        val frames = mutableListOf<PerformancePhase>()
        val timings =
            GroupMembershipTimings(
                begin = { operation ->
                    PerformanceTrace(
                        operation,
                        sessionGeneration = 1L,
                        operationId = 1L,
                        startedAtMs = 0L,
                    )
                },
                nowMs = { 10L },
                record = { _, phase, _ -> frames += phase },
            )
        timings.pendingAccepted("old")
        timings.pendingAccepted("new")
        timings.pendingSettled("old")
        timings.pendingFrame("old")
        timings.pendingFrame("new")
        timings.pendingSettled("new")
        timings.pendingFrame("new")
        assertEquals(
            listOf(
                PerformancePhase.ACCEPTED,
                PerformancePhase.ACCEPTED,
                PerformancePhase.FIRST_LOCAL_FRAME,
            ),
            frames,
        )
    }
}
