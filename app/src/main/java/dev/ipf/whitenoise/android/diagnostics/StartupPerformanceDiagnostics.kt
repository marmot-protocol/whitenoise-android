package dev.ipf.whitenoise.android.diagnostics

import android.os.SystemClock
import dev.ipf.whitenoise.android.state.StartupStageTrace
import dev.ipf.whitenoise.android.state.StartupStageTraceSection

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
        val stageName =
            requireNotNull(startupTraceStageFor(phase)) {
                "No reserved startup trace section for ${phase.wireName}"
            }
        var result = PerformanceResult.SUCCESS
        return StartupStageTrace.trace(
            stage = stageName,
            block = {
                val outcome = runCatching { block() }
                result = if (outcome.isSuccess) PerformanceResult.SUCCESS else PerformanceResult.FAILURE
                outcome.getOrThrow()
            },
            onFinished = { durationMs -> record(phase, durationMs, result) },
        )
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

/** Maps the typed diagnostic phase onto the fixed Perfetto startup vocabulary. */
internal fun startupTraceStageFor(phase: PerformancePhase): String? {
    val stageName = phase.wireName.replace('_', '-')
    return stageName.takeIf { StartupStageTraceSection.sectionFor(it) != null }
}
