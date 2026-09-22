package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceAttachmentState
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger

/** Privacy-bounded trace for one attachment fetch; it never receives attachment identity or content. */
internal class AttachmentFetchDiagnostics private constructor(
    private val trace: PerformanceTrace,
    private val nowMs: () -> Long,
    private val record: (
        PerformanceTrace,
        PerformancePhase,
        Long,
        Long,
        PerformanceResult,
        PerformanceLayer,
        PerformanceAttachmentState?,
    ) -> Unit,
) {
    /** Records one closed attachment phase relative to the operation start. */
    fun phase(
        phase: PerformancePhase,
        result: PerformanceResult = PerformanceResult.SUCCESS,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
        durationMs: Long = 0L,
        attachmentState: PerformanceAttachmentState? = null,
    ) {
        record(
            trace,
            phase,
            (nowMs() - trace.startedAtMs).coerceAtLeast(0L),
            durationMs.coerceAtLeast(0L),
            result,
            layer,
            attachmentState,
        )
    }

    /** Returns a monotonic span start without exposing it outside the process. */
    fun startSpan(): Long = nowMs()

    /** Converts a native state to the closed local schema and records the update. */
    fun transferUpdate(state: AttachmentTransferStateFfi) {
        phase(
            phase = PerformancePhase.ATTACHMENT_TRANSFER_UPDATE,
            result = state.performanceResult(),
            layer = PerformanceLayer.MDK,
            attachmentState = state.toPerformanceAttachmentState(),
        )
    }

    internal companion object {
        /** Starts an explicit or automatic fetch only while the bounded WNPerf session is active. */
        fun begin(
            priority: AttachmentDownloadPriority,
            nowMs: () -> Long = SystemClock::elapsedRealtime,
            begin: (PerformanceOperation, PerformanceTrigger?) -> PerformanceTrace? = PerformanceDiagnostics::begin,
            record: (
                PerformanceTrace,
                PerformancePhase,
                Long,
                Long,
                PerformanceResult,
                PerformanceLayer,
                PerformanceAttachmentState?,
            ) -> Unit = ::recordAttachmentFetchEvent,
        ): AttachmentFetchDiagnostics? {
            val trigger =
                when (priority) {
                    AttachmentDownloadPriority.Interactive -> PerformanceTrigger.EXPLICIT
                    AttachmentDownloadPriority.Automatic -> null
                }
            val trace = begin(PerformanceOperation.ATTACHMENT_FETCH, trigger) ?: return null
            return AttachmentFetchDiagnostics(trace, nowMs, record).also {
                it.phase(
                    phase = PerformancePhase.ATTACHMENT_REQUESTED,
                    result = PerformanceResult.PENDING,
                )
            }
        }
    }
}

/** Serializes a fetch event through the typed WNPerf owner. */
private fun recordAttachmentFetchEvent(
    trace: PerformanceTrace,
    phase: PerformancePhase,
    elapsedMs: Long,
    durationMs: Long,
    result: PerformanceResult,
    layer: PerformanceLayer,
    attachmentState: PerformanceAttachmentState?,
) {
    PerformanceDiagnostics.record(
        trace = trace,
        phase = phase,
        elapsedMs = elapsedMs,
        durationMs = durationMs,
        result = result,
        layer = layer,
        attachmentState = attachmentState,
    )
}

/** Maps native attachment state without serializing its reference or transfer identity. */
@Suppress("MaxLineLength")
private fun AttachmentTransferStateFfi.toPerformanceAttachmentState(): PerformanceAttachmentState = PerformanceAttachmentState.valueOf(name)

/** Classifies only terminal outcome shape, never a raw native error. */
private fun AttachmentTransferStateFfi.performanceResult(): PerformanceResult =
    when (this) {
        AttachmentTransferStateFfi.READY -> PerformanceResult.SUCCESS
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        -> PerformanceResult.FAILURE
        else -> PerformanceResult.PENDING
    }
