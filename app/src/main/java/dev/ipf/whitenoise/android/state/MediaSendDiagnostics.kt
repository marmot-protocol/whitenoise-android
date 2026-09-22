package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage

/** Retains the native upload outcome and records atomic admission when present. */
internal fun DurableComposerMediaUpload.captureForRetry(
    retained: RetainedMediaUpload,
    diagnostics: PendingSendDiagnosticTracker,
    optimisticId: String,
    startedAtMs: Long,
) {
    retained.localAcceptance = acceptance
    retained.recoveredWithoutUpload = recoveredWithoutUpload
    if (acceptance != null) diagnostics.mediaPublishCombined(optimisticId, startedAtMs)
}

/** Starts upload and publish tracing after the media optimistic is visible. */
internal fun PendingSendDiagnosticTracker.startMediaSend(
    optimisticId: String,
    manualRetry: Boolean = false,
) {
    val trace = PerformanceDiagnostics.begin(PerformanceOperation.MEDIA_SEND)
    track(optimisticId, trace)
    milestone(
        optimisticId,
        if (manualRetry) PerformancePhase.MANUAL_RETRY else PerformancePhase.ACCEPTED,
        PerformanceSendStage.OPTIMISTIC,
        result = PerformanceResult.PENDING,
    )
    if (!manualRetry) {
        milestone(
            optimisticId,
            PerformancePhase.OPTIMISTIC_SHOWN,
            PerformanceSendStage.OPTIMISTIC,
            result = PerformanceResult.PENDING,
        )
    }
}

/** Starts a measured media-upload FFI span. */
internal fun PendingSendDiagnosticTracker.beginMediaUpload(optimisticId: String): Long {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_UPLOAD_START,
        PerformanceSendStage.FFI_ADMISSION,
        layer = PerformanceLayer.FFI,
    )
    return traceNowMs()
}

/** Finishes a measured media-upload FFI span. */
internal fun PendingSendDiagnosticTracker.finishMediaUpload(
    optimisticId: String,
    startedAtMs: Long,
) {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_UPLOAD_RETURN,
        PerformanceSendStage.FFI_ADMISSION,
        layer = PerformanceLayer.FFI,
        durationMs = traceNowMs() - startedAtMs,
    )
}

/** Records that retry reused already-uploaded references instead of duplicating blobs. */
internal fun PendingSendDiagnosticTracker.mediaUploadReused(optimisticId: String) {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_UPLOAD_REUSED,
        PerformanceSendStage.FFI_ADMISSION,
        layer = PerformanceLayer.FFI,
    )
}

/** Starts a measured media publication span. */
internal fun PendingSendDiagnosticTracker.beginMediaPublish(optimisticId: String): Long {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_PUBLISH_START,
        PerformanceSendStage.FFI_ADMISSION,
        layer = PerformanceLayer.FFI,
    )
    return traceNowMs()
}

/** Finishes a distinct media-publication FFI span. */
internal fun PendingSendDiagnosticTracker.finishMediaPublish(
    optimisticId: String,
    startedAtMs: Long,
) {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_PUBLISH_RETURN,
        PerformanceSendStage.ACCEPTED_PENDING,
        layer = PerformanceLayer.MDK,
        durationMs = traceNowMs() - startedAtMs,
    )
}

/** Records admission completed inside the same native call as the upload. */
internal fun PendingSendDiagnosticTracker.mediaPublishCombined(
    optimisticId: String,
    startedAtMs: Long,
) {
    milestone(
        optimisticId,
        PerformancePhase.MEDIA_PUBLISH_COMBINED,
        PerformanceSendStage.ACCEPTED_PENDING,
        layer = PerformanceLayer.MDK,
        durationMs = traceNowMs() - startedAtMs,
    )
}

/** Records relay publication without claiming peer receipt. */
internal fun PendingSendDiagnosticTracker.mediaTransportComplete(optimisticId: String) {
    milestone(
        optimisticId,
        PerformancePhase.TRANSPORT_COMPLETE,
        PerformanceSendStage.WAITING_PROJECTION,
        layer = PerformanceLayer.TRANSPORT,
    )
}
