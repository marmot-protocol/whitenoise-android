package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace

// Timings for one page of conversation history. Neither the app's release logs nor MDK's forensics
// stream carry anything about paging, so a report that scrolling up is slow cannot be answered from
// a log. These phases are what a tester's bounded WNPerf session records instead; production
// compiles the facility out entirely.

/** Records one bounded phase of a history page; a no-op outside an active diagnostics session. */
internal fun PerformanceTrace?.recordPhase(
    phase: PerformancePhase,
    startedMs: Long,
    layer: PerformanceLayer = PerformanceLayer.ANDROID,
    count: Int? = null,
) {
    val trace = this ?: return
    val elapsed = SystemClock.elapsedRealtime() - startedMs
    PerformanceDiagnostics.record(
        trace = trace,
        phase = phase,
        elapsedMs = elapsed,
        durationMs = elapsed,
        layer = layer,
        count = count,
    )
}

/** Runs one window command inside its Perfetto slice and records it as the page's `page_window` phase. */
internal inline fun timedWindowCommand(
    trace: PerformanceTrace?,
    command: () -> TimelinePageOutcome?,
): TimelinePageOutcome? {
    val windowStartedMs = SystemClock.elapsedRealtime()
    return tracedPagingSection(ConversationPagingTraceSection.WINDOW, command)
        .also { trace.recordPhase(PerformancePhase.PAGE_WINDOW, windowStartedMs, PerformanceLayer.FFI) }
}

/** Closes a page's trace with what the reader actually got. */
internal fun PerformanceTrace?.recordCompletion(
    load: ConversationPageLoad,
    startedMs: Long,
) {
    val result =
        when (load) {
            ConversationPageLoad.ADVANCED, ConversationPageLoad.NO_PROGRESS -> PerformanceResult.SUCCESS
            ConversationPageLoad.INACTIVE -> PerformanceResult.DROPPED
            else -> PerformanceResult.FAILURE
        }
    val trace = this ?: return
    PerformanceDiagnostics.record(
        trace = trace,
        phase = PerformancePhase.PAGE_COMPLETE,
        elapsedMs = SystemClock.elapsedRealtime() - startedMs,
        result = result,
    )
}
