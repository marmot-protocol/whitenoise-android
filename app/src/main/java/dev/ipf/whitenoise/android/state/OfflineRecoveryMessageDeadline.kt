package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformancePhase

/**
 * The final validated reconnect that owns one offline-recovery cycle (#3723).
 * [generationBaseline] is the newest recovery generation before that reconnect, so earlier
 * flaps cannot supply the cycle's markers. [atMillis] is the last monotonic time the device
 * was still seen offline, which never understates the measured latency.
 */
internal data class OfflineReconnectEdge(
    val generationBaseline: Long,
    val atMillis: Long,
)

/** One monotonic observation of how many visible rows show the cycle's controlled message. */
internal data class ControlledMessageObservation(
    val atMillis: Long,
    val visibleCount: Int,
)

/** Whether the controlled message became visible, exactly once, within the cycle's ceiling. */
internal sealed interface ControlledMessageOutcome {
    /** The message appeared once, [latencyMillis] after the final reconnect edge. */
    data class Completed(
        val latencyMillis: Long,
    ) : ControlledMessageOutcome

    /** The cycle failed for [reason], which names no message text or identifiers. */
    data class Failed(
        val reason: String,
    ) : ControlledMessageOutcome
}

/**
 * Measures the cycle from [edge] to the first observation of exactly one controlled row.
 * A row seen before the edge, a duplicate, a missing row or a completion later than
 * [ceilingMillis] fails, whatever recovery frame markers were recorded meanwhile.
 */
internal fun controlledMessageOutcome(
    edge: OfflineReconnectEdge,
    observations: List<ControlledMessageObservation>,
    ceilingMillis: Long,
): ControlledMessageOutcome {
    val completion = observations.firstOrNull { it.atMillis >= edge.atMillis && it.visibleCount == 1 }
    val latency = completion?.let { it.atMillis - edge.atMillis }
    return when {
        observations.any { it.atMillis < edge.atMillis && it.visibleCount > 0 } ->
            ControlledMessageOutcome.Failed("Controlled message was visible before the final reconnect")
        observations.any { it.visibleCount > 1 } ->
            ControlledMessageOutcome.Failed("Controlled message was visible more than once")
        latency == null -> ControlledMessageOutcome.Failed("Controlled message never became visible")
        latency > ceilingMillis ->
            ControlledMessageOutcome.Failed("Controlled message took $latency ms, over $ceilingMillis ms")
        else -> ControlledMessageOutcome.Completed(latency)
    }
}

/** Returns the first visible-frame generation started by [edge], ignoring older reconnects. */
internal fun recoveryGenerationAfter(
    samples: List<NotificationNetworkRecoverySample>,
    edge: OfflineReconnectEdge,
): Long? =
    samples
        .firstOrNull {
            it.generation > edge.generationBaseline && it.phase == PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME
        }?.generation
