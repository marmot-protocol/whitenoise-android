package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import kotlin.math.ceil

/** p50, p95, and maximum monotonic elapsed time for one recovery phase. */
internal data class OfflineRecoveryPhaseLatency(
    val p50Millis: Long,
    val p95Millis: Long,
    val maximumMillis: Long,
)

/** Numeric-only aggregate emitted by the controlled 20-cycle device scenario. */
internal data class OfflineRecoveryLatencyReport(
    val completedCycles: Int,
    val phaseLatencies: Map<PerformancePhase, OfflineRecoveryPhaseLatency>,
)

/**
 * Aggregates one successful marker per generation and phase. Retry/pending
 * observations cannot make a failed attempt look like a completed cycle.
 */
@Suppress("MaxLineLength") // The explicit sample and report types make this test-facing boundary self-describing.
internal fun offlineRecoveryLatencyReport(samples: List<NotificationNetworkRecoverySample>): OfflineRecoveryLatencyReport {
    val successful = samples.filter { it.result == PerformanceResult.SUCCESS }
    val completedGenerations =
        successful
            .asSequence()
            .filter { it.phase == PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME }
            .map { it.generation }
            .toSet()
    val phaseLatencies =
        successful
            .filter { it.generation in completedGenerations }
            .groupBy(NotificationNetworkRecoverySample::phase)
            .mapValues { (_, phaseSamples) ->
                phaseLatency(phaseSamples.map(NotificationNetworkRecoverySample::elapsedMillis))
            }
    return OfflineRecoveryLatencyReport(completedGenerations.size, phaseLatencies)
}

/** Calculates nearest-rank percentiles for a non-empty phase sample. */
private fun phaseLatency(samples: List<Long>): OfflineRecoveryPhaseLatency {
    require(samples.isNotEmpty())
    val ordered = samples.sorted()
    return OfflineRecoveryPhaseLatency(
        p50Millis = nearestRank(ordered, P50_PERCENTILE),
        p95Millis = nearestRank(ordered, P95_PERCENTILE),
        maximumMillis = ordered.last(),
    )
}

private const val P50_PERCENTILE = 0.50
private const val P95_PERCENTILE = 0.95

/** Returns the conventional nearest-rank percentile from sorted values. */
private fun nearestRank(
    ordered: List<Long>,
    percentile: Double,
): Long {
    val index = (ceil(percentile * ordered.size).toInt() - 1).coerceIn(0, ordered.lastIndex)
    return ordered[index]
}
