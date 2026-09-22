package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceConnectivity
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceSendStage
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One privacy-bounded WNPerf event emitted by [PendingSendDiagnosticTracker]. */
internal data class PendingSendDiagnosticEvent(
    val trace: PerformanceTrace,
    val phase: PerformancePhase,
    val elapsedMs: Long,
    val result: PerformanceResult,
    val layer: PerformanceLayer,
    val attempt: Int?,
    val sendStage: PerformanceSendStage,
    val connectivity: PerformanceConnectivity? = null,
)

/** Maps the existing privacy-safe aggregate signals to the closed performance schema. */
internal fun ConnectivitySignals.toPerformanceConnectivity(): PerformanceConnectivity =
    when {
        !hasValidatedInternet -> PerformanceConnectivity.OFFLINE
        relaysConnected -> PerformanceConnectivity.ONLINE_WITH_RELAY
        else -> PerformanceConnectivity.ONLINE_NO_RELAY
    }

/**
 * Keeps opt-in send diagnostics alive across conversation-controller replacement.
 *
 * This is short-lived diagnostic lifecycle state, not a protocol cache: keys are
 * local optimistic UUIDs, entries are bounded, and every entry expires with the
 * WNPerf session. No account, group, event id, message body, or relay identity is
 * stored or serialized.
 */
internal class PendingSendDiagnosticTracker(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val connectivity: () -> PerformanceConnectivity,
    private val record: (PendingSendDiagnosticEvent) -> Unit = ::recordPendingSendDiagnosticEvent,
) {
    private data class Entry(
        val trace: PerformanceTrace,
        val job: Job,
        var stage: PerformanceSendStage = PerformanceSendStage.OPTIMISTIC,
        var attempt: Int? = null,
        var timelineSettled: Boolean = false,
        var chatListSettled: Boolean = false,
    )

    private data class Snapshot(
        val trace: PerformanceTrace,
        val stage: PerformanceSendStage,
        val attempt: Int?,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    /** Begins bounded 10-second and 60-second checkpoints for an active opt-in trace. */
    fun track(
        optimisticId: String,
        trace: PerformanceTrace?,
    ) {
        if (trace == null) return
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(FIRST_CHECKPOINT_MS)
                checkpoint(optimisticId, PerformancePhase.PENDING_CHECKPOINT_10S)
                delay(SECOND_CHECKPOINT_MS - FIRST_CHECKPOINT_MS)
                checkpoint(optimisticId, PerformancePhase.PENDING_CHECKPOINT_60S)
                delay(WNPERF_SESSION_DURATION_MS - SECOND_CHECKPOINT_MS)
                removeIfOwned(optimisticId, job)
            }
        val evicted =
            synchronized(lock) {
                val replaced = entries.put(optimisticId, Entry(trace = trace, job = job))?.job
                val overflow =
                    if (entries.size > MAX_TRACKED_SENDS) {
                        val oldestKey = entries.keys.first()
                        entries.remove(oldestKey)?.job
                    } else {
                        null
                    }
                listOfNotNull(replaced, overflow)
            }
        evicted.forEach(Job::cancel)
        job.start()
    }

    /** Updates the closed stage sampled by future checkpoints. */
    fun update(
        optimisticId: String,
        stage: PerformanceSendStage,
        attempt: Int? = null,
    ) {
        synchronized(lock) {
            entries[optimisticId]?.let { entry ->
                entry.stage = stage
                if (attempt != null) entry.attempt = attempt
            }
        }
    }

    /** Records a typed milestone while atomically advancing the sampled send stage. */
    fun milestone(
        optimisticId: String,
        phase: PerformancePhase,
        stage: PerformanceSendStage,
        result: PerformanceResult = PerformanceResult.PENDING,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
        attempt: Int? = null,
    ) {
        val snapshot =
            synchronized(lock) {
                entries[optimisticId]?.let { entry ->
                    entry.stage = stage
                    if (attempt != null) entry.attempt = attempt
                    entry.snapshot()
                }
            } ?: return
        emit(snapshot, phase, result, layer)
    }

    /** Records one projection surface without ending the other surface's trace opportunity. */
    fun surfaceSettled(
        optimisticId: String,
        phase: PerformancePhase,
    ) {
        require(phase == PerformancePhase.TIMELINE_SETTLED || phase == PerformancePhase.CHAT_LIST_SETTLED)
        var completedJob: Job? = null
        val snapshot =
            synchronized(lock) {
                val entry = entries[optimisticId] ?: return@synchronized null
                val alreadyRecorded =
                    when (phase) {
                        PerformancePhase.TIMELINE_SETTLED -> entry.timelineSettled.also { entry.timelineSettled = true }
                        PerformancePhase.CHAT_LIST_SETTLED ->
                            entry.chatListSettled.also {
                                entry.chatListSettled = true
                            }
                    }
                if (alreadyRecorded) return@synchronized null
                val captured = entry.snapshot()
                if (entry.timelineSettled && entry.chatListSettled) {
                    entries.remove(optimisticId)
                    completedJob = entry.job
                }
                captured
            } ?: return
        emit(snapshot, phase, PerformanceResult.SUCCESS, PerformanceLayer.ANDROID)
        completedJob?.cancel()
    }

    /** Records a terminal non-projection outcome and releases its watchdog. */
    fun complete(
        optimisticId: String,
        phase: PerformancePhase,
        result: PerformanceResult = PerformanceResult.SUCCESS,
        layer: PerformanceLayer = PerformanceLayer.ANDROID,
    ) {
        val entry = synchronized(lock) { entries.remove(optimisticId) } ?: return
        emit(entry.snapshot(), phase, result, layer)
        entry.job.cancel()
    }

    /** Releases a trace when an existing call site already emitted its terminal phase. */
    fun forget(optimisticId: String) {
        synchronized(lock) { entries.remove(optimisticId) }?.job?.cancel()
    }

    /** Cancels every watchdog on account switch/sign-out. */
    fun clear() {
        val jobs =
            synchronized(lock) {
                entries.values.map(Entry::job).also { entries.clear() }
            }
        jobs.forEach(Job::cancel)
    }

    private fun checkpoint(
        optimisticId: String,
        phase: PerformancePhase,
    ) {
        val snapshot =
            synchronized(lock) {
                entries[optimisticId]
                    ?.takeUnless { it.timelineSettled }
                    ?.snapshot()
            } ?: return
        record(
            PendingSendDiagnosticEvent(
                trace = snapshot.trace,
                phase = phase,
                elapsedMs = (nowMs() - snapshot.trace.startedAtMs).coerceAtLeast(0L),
                result = PerformanceResult.PENDING,
                layer = PerformanceLayer.ANDROID,
                attempt = snapshot.attempt,
                sendStage = snapshot.stage,
                connectivity = connectivity(),
            ),
        )
    }

    private fun emit(
        snapshot: Snapshot,
        phase: PerformancePhase,
        result: PerformanceResult,
        layer: PerformanceLayer,
    ) {
        record(
            PendingSendDiagnosticEvent(
                trace = snapshot.trace,
                phase = phase,
                elapsedMs = (nowMs() - snapshot.trace.startedAtMs).coerceAtLeast(0L),
                result = result,
                layer = layer,
                attempt = snapshot.attempt,
                sendStage = snapshot.stage,
            ),
        )
    }

    /** Copies mutable tracker state before invoking the recorder outside the lock. */
    private fun Entry.snapshot(): Snapshot = Snapshot(trace = trace, stage = stage, attempt = attempt)

    /** Expires an entry only when the scheduled watchdog still owns it. */
    private fun removeIfOwned(
        optimisticId: String,
        job: Job,
    ) {
        synchronized(lock) {
            if (entries[optimisticId]?.job == job) entries.remove(optimisticId)
        }
    }

    internal companion object {
        const val FIRST_CHECKPOINT_MS = 10_000L
        const val SECOND_CHECKPOINT_MS = 60_000L
        const val MAX_TRACKED_SENDS = 64
        private const val WNPERF_SESSION_DURATION_MS = 30L * 60L * 1_000L
    }
}

/** Marks native durable ownership without inventing an unavailable inner MDK queue phase. */
internal fun PendingSendDiagnosticTracker.recordAcceptedPending(
    optimisticId: String,
    acceptedPending: Boolean,
) {
    if (!acceptedPending) return
    milestone(
        optimisticId,
        PerformancePhase.DURABLE_ACCEPTED,
        PerformanceSendStage.ACCEPTED_PENDING,
        layer = PerformanceLayer.MDK,
    )
    milestone(
        optimisticId,
        PerformancePhase.ENGINE_PHASE_UNAVAILABLE,
        PerformanceSendStage.ACCEPTED_PENDING,
        layer = PerformanceLayer.MDK,
    )
}

/** Routes tracker events through the privacy-reviewed typed WNPerf emitter. */
private fun recordPendingSendDiagnosticEvent(event: PendingSendDiagnosticEvent) {
    PerformanceDiagnostics.record(
        trace = event.trace,
        phase = event.phase,
        elapsedMs = event.elapsedMs,
        result = event.result,
        layer = event.layer,
        attempt = event.attempt,
        sendStage = event.sendStage,
        connectivity = event.connectivity,
    )
}
