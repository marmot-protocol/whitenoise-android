package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.CancellationException

/** One closed forward phase as handed to the typed recorder, with no identity or payload fields. */
internal typealias ForwardPhaseRecorder = (
    trace: PerformanceTrace,
    phase: PerformancePhase,
    elapsedMs: Long,
    durationMs: Long,
    result: PerformanceResult,
    layer: PerformanceLayer,
    count: Int?,
) -> Unit

/**
 * Privacy-bounded trace for one forwarding operation. It receives only phase
 * names, monotonic durations, outcome shapes and bounded counts, never group,
 * account or message identifiers, file names, hashes, URLs or error text, so
 * one report can tell a slow source read from a slow destination upload,
 * commit-lock wait or publication. It exists only while a diagnostics session
 * is active, so an inactive build pays one null check per phase.
 */
@Suppress("TooManyFunctions") // One owner names every closed forward phase, so no call site can supply its own.
internal class ForwardDiagnostics private constructor(
    private val trace: PerformanceTrace,
    private val nowMs: () -> Long,
    private val record: ForwardPhaseRecorder,
) {
    /** Returns a monotonic span start that never leaves the process. */
    fun startSpan(): Long = nowMs()

    /** Records that the optimistic source reference was resolved through native history. */
    fun sourceReferenceResolved(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.FORWARD_SOURCE_REFERENCE_RESOLVED, result, PerformanceLayer.MDK, since(startedAtMs))

    /**
     * Records the local probe for one source attachment: SUCCESS when a local layer held the bytes, attributed to
     * storage for the Android caches or to mdk when MarmotKit's retained copy served them.
     */
    fun sourceLookup(
        hit: Boolean,
        startedAtMs: Long,
        native: Boolean = false,
    ) {
        val result = if (hit) PerformanceResult.SUCCESS else PerformanceResult.PENDING
        val layer = if (native) PerformanceLayer.MDK else PerformanceLayer.STORAGE
        phase(PerformancePhase.FORWARD_SOURCE_LOOKUP, result, layer, since(startedAtMs))
    }

    /** Marks the start of the native source download so a missing return identifies a stalled source. */
    fun sourceDownloadStart() {
        phase(PerformancePhase.FORWARD_SOURCE_DOWNLOAD_START, PerformanceResult.PENDING, PerformanceLayer.MDK)
    }

    /** Closes the native source download span with its outcome shape. */
    fun sourceDownloadReturn(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.FORWARD_SOURCE_DOWNLOAD_RETURN, result, PerformanceLayer.MDK, since(startedAtMs))

    /** Closes one attachment's whole materialization, from reference to retained plaintext copy. */
    fun sourceReady(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.FORWARD_SOURCE_READY, result, PerformanceLayer.ANDROID, since(startedAtMs))

    /** Marks the start of the destination upload FFI span. */
    fun uploadStart() = phase(PerformancePhase.MEDIA_UPLOAD_START, PerformanceResult.PENDING, PerformanceLayer.FFI)

    /** Closes the destination upload FFI span with its outcome shape. */
    fun uploadReturn(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.MEDIA_UPLOAD_RETURN, result, PerformanceLayer.FFI, since(startedAtMs))

    /** Records how long the destination batch waited for the shared group commit lock. */
    fun commitLockAcquired(requestedAtMs: Long) =
        phase(
            PerformancePhase.COMMIT_LOCK_ACQUIRED,
            PerformanceResult.SUCCESS,
            PerformanceLayer.ANDROID,
            since(requestedAtMs),
        )

    /** Marks the start of one destination publication. */
    fun publishStart() = phase(PerformancePhase.MEDIA_PUBLISH_START, PerformanceResult.PENDING, PerformanceLayer.MDK)

    /** Closes one destination publication with its outcome shape, never its error. */
    fun publishReturn(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.MEDIA_PUBLISH_RETURN, result, PerformanceLayer.MDK, since(startedAtMs))

    /** Marks the start of uncertain-publication convergence on a retry. */
    fun convergenceStart() {
        phase(PerformancePhase.CONVERGENCE_START, PerformanceResult.PENDING, PerformanceLayer.MDK)
    }

    /** Closes the convergence call with its outcome shape. */
    fun convergenceReturn(
        startedAtMs: Long,
        result: PerformanceResult,
    ) = phase(PerformancePhase.CONVERGENCE_RETURN, result, PerformanceLayer.MDK, since(startedAtMs))

    /** Records a terminal operation snapshot: completed, cancelled (dropped) or failed, with completed targets. */
    fun terminal(snapshot: ForwardOperationSnapshot) {
        val result =
            when (snapshot.phase) {
                ForwardOperationPhase.Completed -> PerformanceResult.SUCCESS
                ForwardOperationPhase.Cancelled -> PerformanceResult.DROPPED
                else -> PerformanceResult.FAILURE
            }
        phase(PerformancePhase.FORWARD_COMPLETE, result, PerformanceLayer.ANDROID, count = snapshot.completedTargets)
    }

    /** Emits one closed phase relative to the operation start. */
    private fun phase(
        phase: PerformancePhase,
        result: PerformanceResult,
        layer: PerformanceLayer,
        durationMs: Long = 0L,
        count: Int? = null,
    ) = record(trace, phase, since(trace.startedAtMs), durationMs.coerceAtLeast(0L), result, layer, count)

    /** Milliseconds since a span start, never negative. */
    private fun since(startedAtMs: Long): Long = (nowMs() - startedAtMs).coerceAtLeast(0L)

    internal companion object {
        /** Starts a forward trace only while the bounded local diagnostics session is active. */
        fun begin(
            nowMs: () -> Long = SystemClock::elapsedRealtime,
            begin: (PerformanceOperation) -> PerformanceTrace? = { PerformanceDiagnostics.begin(it) },
            record: ForwardPhaseRecorder = ::recordForwardPhase,
        ): ForwardDiagnostics? {
            val trace = begin(PerformanceOperation.MESSAGE_FORWARD) ?: return null
            return ForwardDiagnostics(trace, nowMs, record)
        }
    }
}

/**
 * Runs one measured span and closes it with the outcome shape only. A
 * cancellation closes as dropped and a failure as failure, and both rethrow
 * unchanged so the forward's own classification is not affected.
 */
@Suppress("TooGenericExceptionCaught") // Every non-cancellation failure closes the span with the same shape.
internal suspend fun <T> ForwardDiagnostics?.span(
    start: ForwardDiagnostics.() -> Unit,
    finish: ForwardDiagnostics.(startedAtMs: Long, result: PerformanceResult) -> Unit,
    block: suspend () -> T,
): T {
    if (this == null) return block()
    start()
    val startedAtMs = startSpan()
    return try {
        block().also { finish(startedAtMs, PerformanceResult.SUCCESS) }
    } catch (cancellation: CancellationException) {
        finish(startedAtMs, PerformanceResult.DROPPED)
        throw cancellation
    } catch (throwable: Throwable) {
        finish(startedAtMs, PerformanceResult.FAILURE)
        throw throwable
    }
}

/** Serializes a forward phase through the typed diagnostics owner. */
private fun recordForwardPhase(
    trace: PerformanceTrace,
    phase: PerformancePhase,
    elapsedMs: Long,
    durationMs: Long,
    result: PerformanceResult,
    layer: PerformanceLayer,
    count: Int?,
) {
    PerformanceDiagnostics.record(
        trace = trace,
        phase = phase,
        elapsedMs = elapsedMs,
        durationMs = durationMs,
        result = result,
        layer = layer,
        count = count,
    )
}
