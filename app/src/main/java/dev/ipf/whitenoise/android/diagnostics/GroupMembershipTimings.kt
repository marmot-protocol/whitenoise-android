package dev.ipf.whitenoise.android.diagnostics

import android.os.SystemClock

private const val MAX_MEMBERSHIP_PROJECTION_TRACES = 32

/** Controller-local, bounded timings. Row/request keys are used in memory and never sent to the emitter. */
internal class GroupMembershipTimings(
    private val begin: (PerformanceOperation) -> PerformanceTrace? = { PerformanceDiagnostics.begin(it) },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val record: (PerformanceTrace, PerformancePhase, Long) -> Unit = { trace, phase, elapsed ->
        PerformanceDiagnostics.record(trace, phase, elapsedMs = elapsed)
    },
) {
    private var pendingId: String? = null
    private var pendingTrace: PerformanceTrace? = null
    private var pendingFrameRecorded = false
    private val projections = linkedMapOf<String, PerformanceTrace>()

    fun pendingAccepted(id: String) {
        pendingId = id
        pendingFrameRecorded = false
        pendingTrace = begin(PerformanceOperation.GROUP_MEMBERSHIP_PENDING)
        pendingTrace?.let { record(it, PerformancePhase.ACCEPTED, 0L) }
    }

    fun pendingFrame(id: String) {
        if (pendingId != id || pendingFrameRecorded) return
        pendingFrameRecorded = true
        pendingTrace?.let { recordFrame(it) }
    }

    fun pendingSettled(id: String) {
        if (pendingId != id) return
        pendingId = null
        pendingTrace = null
    }

    fun projectionArrived(id: String) {
        if (id in projections) return
        val trace = begin(PerformanceOperation.GROUP_MEMBERSHIP_PROJECTION) ?: return
        if (projections.size == MAX_MEMBERSHIP_PROJECTION_TRACES) projections.remove(projections.keys.first())
        projections[id] = trace
        record(trace, PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED, 0L)
    }

    fun projectionFrame(id: String) {
        projections.remove(id)?.let { recordFrame(it) }
    }

    private fun recordFrame(trace: PerformanceTrace) {
        record(trace, PerformancePhase.FIRST_LOCAL_FRAME, (nowMs() - trace.startedAtMs).coerceAtLeast(0L))
    }
}
