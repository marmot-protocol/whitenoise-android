package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AppPerformanceOperationSnapshotFfi

/** Closed native phase labels used only by local synthetic measurement tests. */
internal enum class MediaProbeNativePhase(
    val wireName: String,
) {
    DOWNLOAD("download"),
    QUEUE_WAIT("queue_wait"),
    PREPARATION("preparation"),
    HOST_SETUP("host_setup"),
    RESPONSE_HEADERS("response_headers"),
    FIRST_BYTE("first_byte"),
    BODY_TRANSFER("body_transfer"),
    LOCATOR_FAILOVER("locator_failover"),
    CIPHERTEXT_VERIFY("ciphertext_verify"),
    DECRYPT("decrypt"),
    PLAINTEXT_VERIFY("plaintext_verify"),
}

/** Reports only new counters and fixed histogram buckets, never cumulative history. */
internal fun mediaProbePhaseReport(
    phase: MediaProbeNativePhase,
    before: AppPerformanceOperationSnapshotFfi,
    after: AppPerformanceOperationSnapshotFfi,
): List<String> =
    buildList {
        val bounds = before.durationMs.buckets.map { it.upperBoundMs }
        require(bounds == after.durationMs.buckets.map { it.upperBoundMs }) { "Native histogram bounds changed" }
        val ordered = bounds.zipWithNext().all { (left, right) -> left < right }
        require(ordered) { "Native histogram bounds are not ordered" }
        val prefix = "phase=${phase.wireName}"
        add(
            "$prefix attempts=${delta(before.attempts, after.attempts)} " +
                "successes=${delta(before.successes, after.successes)} " +
                "failures=${delta(before.failures, after.failures)} " +
                "duration_sum_ms=${delta(before.durationMs.sumMs, after.durationMs.sumMs)} " +
                "overflow_count=${delta(before.durationMs.overflowCount, after.durationMs.overflowCount)}",
        )
        before.durationMs.buckets.zip(after.durationMs.buckets).forEach { (previous, current) ->
            val count = delta(previous.count, current.count)
            if (count > 0uL) add("$prefix upper_bound_ms=${current.upperBoundMs} count=$count")
        }
    }

/** Rejects reset/saturated snapshots rather than inventing an exact duration or unsigned count. */
private fun delta(
    before: ULong,
    after: ULong,
): ULong {
    require(after >= before) { "Native measurement counter reset" }
    require(after < ULong.MAX_VALUE) { "Native measurement counter saturated" }
    return after - before
}
