package dev.ipf.whitenoise.android.diagnostics

import android.os.SystemClock

/** Process-local startup trace with no caller-controlled fields. */
internal class StartupPerformanceDiagnostics(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val startedAtMs = nowMs()
    private val trace = PerformanceDiagnostics.begin(PerformanceOperation.APP_START)

    suspend fun <T> stage(
        phase: PerformancePhase,
        block: suspend () -> T,
    ): T {
        val stageStartedAtMs = nowMs()
        val outcome = runCatching { block() }
        val result = if (outcome.isSuccess) PerformanceResult.SUCCESS else PerformanceResult.FAILURE
        record(phase, nowMs() - stageStartedAtMs, result)
        return outcome.getOrThrow()
    }

    fun record(
        phase: PerformancePhase,
        durationMs: Long = 0L,
        result: PerformanceResult = PerformanceResult.SUCCESS,
    ) {
        PerformanceDiagnostics.record(
            trace = trace,
            phase = phase,
            elapsedMs = nowMs() - startedAtMs,
            durationMs = durationMs,
            result = result,
            layer = startupLayer(phase),
        )
    }

    private fun startupLayer(phase: PerformancePhase): PerformanceLayer =
        when (phase) {
            PerformancePhase.DRAFT_RECONCILIATION,
            PerformancePhase.CACHED_CHAT_ROWS_READY,
            -> PerformanceLayer.STORAGE
            PerformancePhase.CLIENT_CONSTRUCTION,
            PerformancePhase.PRIVACY_RUNTIME_CONFIGURATION,
            PerformancePhase.FAILED_RUNTIME_CLOSE,
            PerformancePhase.ACCOUNT_ACTIVATION,
            -> PerformanceLayer.FFI
            PerformancePhase.MARMOT_START -> PerformanceLayer.MDK
            PerformancePhase.RELAY_CATCH_UP_READY -> PerformanceLayer.TRANSPORT
            else -> PerformanceLayer.ANDROID
        }
}
