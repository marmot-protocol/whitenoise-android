package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.marmotkit.AppPerformanceOperationSnapshotFfi
import dev.ipf.marmotkit.AppPerformanceSnapshotFfi
import dev.ipf.marmotkit.NativeStartupSnapshotFfi
import java.util.concurrent.atomic.AtomicReference

internal const val NATIVE_PERFORMANCE_SNAPSHOT_FILE_NAME = "native-performance-snapshot.txt"
private const val MAX_NATIVE_SNAPSHOT_CHARS = 12 * 1024
private const val NATIVE_CAP_MARKER = "native_startup_records_capped=true\n"
private const val MAX_NATIVE_ACTIVE_SCOPES = 16
private const val MAX_NATIVE_HISTORY_EVENTS = 96

/** One diagnostic-build runtime owner; ordinary builds never enable or query native capture. */
internal class NativeStartupDiagnosticCapture(
    private val enabled: Boolean,
) {
    private class Source(
        val owner: Any,
        val read: () -> NativeStartupSnapshotFfi,
        val disable: () -> Unit,
    )

    private val source = AtomicReference<Source?>()

    /**
     * Retires the outgoing capture before enabling its replacement; rebinding the same owner is inert.
     * Failed enable/disable callbacks never prevent the app from replacing its runtime. The serialized
     * transition prevents a concurrent old-owner unbind from disabling the newly published capture.
     */
    @Synchronized
    fun bind(
        owner: Any,
        enable: () -> Unit,
        read: () -> NativeStartupSnapshotFfi,
        disable: () -> Unit,
    ) {
        if (!enabled) return
        if (source.get()?.owner === owner) return
        val bound = Source(owner, read, disable)
        source.getAndSet(bound)?.let { runCatching(it.disable) }
        if (runCatching(enable).isFailure) {
            source.compareAndSet(bound, null)
            runCatching(bound.disable)
        }
    }

    /** Disables only the matching runtime owner; a stale teardown cannot retire its replacement. */
    @Synchronized
    fun unbind(owner: Any) {
        val bound = source.get() ?: return
        if (bound.owner === owner && source.compareAndSet(bound, null)) runCatching(bound.disable)
    }

    /** Call on the existing export IO owner; a failed/stale capture keeps host output usable. */
    fun snapshot(): String? {
        if (!enabled) return null
        val bound = source.get()
        val snapshot = bound?.let { runCatching(it.read).getOrNull() }
        return if (snapshot == null || source.get() !== bound) {
            "native_startup=unavailable\n"
        } else {
            formatNativeStartupSnapshot(snapshot)
        }
    }
}

/**
 * Exports fixed native enums and anonymous numbers, capped at 16 active scopes and 96 recent events.
 * Native history is chronological, so events are reversed to preserve the newest observations when
 * the 12 KiB limit is reached. The pinned SDK's enum names and all other fields are ASCII; character
 * and UTF-8 byte counts therefore agree. Recheck that invariant when adopting another SDK release.
 */
internal fun formatNativeStartupSnapshot(snapshot: NativeStartupSnapshotFfi): String =
    buildString {
        appendLine("native_startup schema=1 enabled=${snapshot.enabled}")
        appendLine("dropped_events=${snapshot.droppedEvents} dropped_scopes=${snapshot.droppedScopes}")
        appendLine("history_order=newest_first")
        val observations =
            snapshot.active.take(MAX_NATIVE_ACTIVE_SCOPES).map { "active" to it } +
                snapshot.events
                    .takeLast(MAX_NATIVE_HISTORY_EVENTS)
                    .asReversed()
                    .map { "event" to it }
        for ((kind, observation) in observations) {
            val line =
                "$kind stage=${observation.stage.name} outcome=${observation.outcome.name} " +
                    "elapsed_ms=${observation.elapsedMs} stage_elapsed_ms=${observation.stageElapsedMs} " +
                    "migration=${observation.migrationVersion?.toString() ?: "none"} " +
                    "migrations_completed=${observation.migrationsCompleted}\n"
            if (length + line.length + NATIVE_CAP_MARKER.length > MAX_NATIVE_SNAPSHOT_CHARS) {
                append(NATIVE_CAP_MARKER)
                break
            }
            append(line)
        }
    }

/**
 * Exports only reviewed, process-cumulative operation totals and migration probe counts.
 * Native operation-name strings, arbitrary DTO text and other counters are deliberately omitted;
 * this report is neither a per-account measurement nor a delta for the current capture session.
 */
internal fun formatNativePerformanceSnapshot(snapshot: AppPerformanceSnapshotFfi): String =
    buildString {
        appendLine("native_performance schema=1 scope=process_cumulative selected_operations=true")
        val operations =
            listOf(
                "app_start" to snapshot.appStart,
                "account_open" to snapshot.accountOpen,
                "group_hydration" to snapshot.accountGroupHydration,
                "profile_load" to snapshot.accountProfileLoad,
                "group_read_snapshot" to snapshot.accountGroupReadSnapshot,
                "account_catch_up" to snapshot.accountCatchUp,
                "message_send" to snapshot.outboundMessageSend,
                "message_queue_wait" to snapshot.outboundMessageQueueWait,
                "message_local_projection" to snapshot.outboundMessageLocalProjection,
                "message_local_accept" to snapshot.outboundMessageLocalAccept,
                "message_publish" to snapshot.outboundMessagePublish,
            )
        operations.forEach { (name, operation) -> appendPerformanceOperation(name, operation) }
        appendLine(
            "migration_probe_runs=${snapshot.sqlcipherMigrationProbeRuns} " +
                "migration_probe_skips=${snapshot.sqlcipherMigrationProbeSkips}",
        )
    }

private fun StringBuilder.appendPerformanceOperation(
    name: String,
    operation: AppPerformanceOperationSnapshotFfi,
) {
    appendLine(
        "$name attempts=${operation.attempts} successes=${operation.successes} failures=${operation.failures} " +
            "duration_sum_ms=${operation.durationMs.sumMs} duration_overflow=${operation.durationMs.overflowCount}",
    )
}

/** The existing whole-export byte ceiling wins; oversize native details never discard the host receipt. */
internal fun withNativeStartupSnapshot(
    host: String,
    native: String?,
): String {
    if (native == null) return host
    val combined = "$host\n$native"
    return if (combined.toByteArray(Charsets.UTF_8).size <= MAX_STARTUP_REPORT_BYTES) combined else host
}
