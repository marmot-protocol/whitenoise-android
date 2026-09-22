package dev.ipf.whitenoise.android.state

import android.os.SystemClock
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

/** Monotonic clock shared by privacy-bounded send spans. */
internal fun traceNowMs(): Long = SystemClock.elapsedRealtime()

/** Emits a typed send phase without accepting payload or protocol identifiers. */
internal fun sendTrace(
    trace: PerformanceTrace?,
    phase: PerformancePhase,
    elapsedMs: Long? = null,
    durationMs: Long = 0L,
    result: PerformanceResult = PerformanceResult.SUCCESS,
    layer: PerformanceLayer = PerformanceLayer.ANDROID,
    attempt: Int? = null,
    connectedRelays: Int? = null,
    totalRelays: Int? = null,
    count: Int? = null,
) {
    if (trace == null) return
    PerformanceDiagnostics.record(
        trace = trace,
        phase = phase,
        elapsedMs = elapsedMs ?: (traceNowMs() - trace.startedAtMs),
        durationMs = durationMs,
        result = result,
        layer = layer,
        attempt = attempt,
        connectedRelays = connectedRelays,
        totalRelays = totalRelays,
        count = count,
    )
}

/** One privacy-bounded WNPerf event emitted by [PendingSendDiagnosticTracker]. */
internal data class PendingSendDiagnosticEvent(
    val trace: PerformanceTrace,
    val phase: PerformancePhase,
    val elapsedMs: Long,
    val durationMs: Long,
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

/** Creates the process-owned tracker without growing the already capped app-state source. */
internal fun createPendingSendDiagnosticTracker(
    scope: CoroutineScope,
    connectivity: () -> PerformanceConnectivity,
): PendingSendDiagnosticTracker =
    PendingSendDiagnosticTracker(
        scope = scope,
        nowMs = SystemClock::elapsedRealtime,
        connectivity = connectivity,
    )

/**
 * Keeps opt-in send diagnostics alive across conversation-controller replacement.
 *
 * This is short-lived diagnostic lifecycle state, not a protocol cache: keys are
 * local optimistic UUIDs and canonical aliases are bounded, and every entry
 * expires with the WNPerf session. No identifier, message body, or relay identity
 * is serialized.
 */
@Suppress("TooManyFunctions") // Atomic lifecycle operations keep one lock owner for a bounded in-flight send.
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
    private val aliases = linkedMapOf<String, String>()

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
                val replaced = removeEntryLocked(optimisticId)?.job
                entries[optimisticId] = Entry(trace = trace, job = job)
                val overflow =
                    if (entries.size > MAX_TRACKED_SENDS) {
                        val oldestKey = entries.keys.first()
                        removeEntryLocked(oldestKey)?.job
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
            entries[resolveKeyLocked(optimisticId)]?.let { entry ->
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
        durationMs: Long = 0L,
    ) {
        val snapshot =
            synchronized(lock) {
                entries[resolveKeyLocked(optimisticId)]?.let { entry ->
                    entry.stage = stage
                    if (attempt != null) entry.attempt = attempt
                    entry.snapshot()
                }
            } ?: return
        emit(snapshot, phase, result, layer, durationMs)
    }

    /** Lets a later authoritative projection settle the same process-local operation. */
    fun alias(
        optimisticId: String,
        canonicalId: String?,
    ) {
        val alias = canonicalId?.takeIf(String::isNotBlank) ?: return
        synchronized(lock) {
            val root = resolveKeyLocked(optimisticId)
            if (root in entries && alias != root) {
                aliases.entries.removeAll { (_, owner) -> owner == root }
                aliases[alias] = root
            }
        }
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
                val root = resolveKeyLocked(optimisticId)
                val entry = entries[root] ?: return@synchronized null
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
                    removeEntryLocked(root)
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
        val entry = synchronized(lock) { removeEntryLocked(resolveKeyLocked(optimisticId)) } ?: return
        emit(entry.snapshot(), phase, result, layer)
        entry.job.cancel()
    }

    /** Releases a trace when an existing call site already emitted its terminal phase. */
    fun forget(optimisticId: String) {
        synchronized(lock) { removeEntryLocked(resolveKeyLocked(optimisticId)) }?.job?.cancel()
    }

    /** Cancels every watchdog on account switch/sign-out. */
    fun clear() {
        val jobs =
            synchronized(lock) {
                entries.values.map(Entry::job).also {
                    entries.clear()
                    aliases.clear()
                }
            }
        jobs.forEach(Job::cancel)
    }

    private fun checkpoint(
        optimisticId: String,
        phase: PerformancePhase,
    ) {
        val snapshot =
            synchronized(lock) {
                entries[resolveKeyLocked(optimisticId)]
                    ?.takeUnless { it.timelineSettled }
                    ?.snapshot()
            } ?: return
        record(
            PendingSendDiagnosticEvent(
                trace = snapshot.trace,
                phase = phase,
                elapsedMs = (nowMs() - snapshot.trace.startedAtMs).coerceAtLeast(0L),
                durationMs = 0L,
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
        durationMs: Long = 0L,
    ) {
        record(
            PendingSendDiagnosticEvent(
                trace = snapshot.trace,
                phase = phase,
                elapsedMs = (nowMs() - snapshot.trace.startedAtMs).coerceAtLeast(0L),
                durationMs = durationMs,
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
            val root = resolveKeyLocked(optimisticId)
            if (entries[root]?.job == job) removeEntryLocked(root)
        }
    }

    /** Resolves an authoritative id without exposing it to the serialized event. */
    private fun resolveKeyLocked(id: String): String = aliases[id] ?: id

    /** Removes one root and all of its bounded authoritative aliases. */
    private fun removeEntryLocked(root: String): Entry? {
        aliases.entries.removeAll { (_, owner) -> owner == root }
        return entries.remove(root)
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

/** Records the authoritative timeline handoff and leaves chat-list settlement independently observable. */
internal fun PendingSendDiagnosticTracker.recordEchoReconcile(optimisticId: String) {
    milestone(
        optimisticId,
        PerformancePhase.ECHO_RECONCILE,
        PerformanceSendStage.WAITING_PROJECTION,
        result = PerformanceResult.SUCCESS,
    )
    surfaceSettled(optimisticId, PerformancePhase.TIMELINE_SETTLED)
}

/** Routes tracker events through the privacy-reviewed typed WNPerf emitter. */
private fun recordPendingSendDiagnosticEvent(event: PendingSendDiagnosticEvent) {
    PerformanceDiagnostics.record(
        trace = event.trace,
        phase = event.phase,
        elapsedMs = event.elapsedMs,
        durationMs = event.durationMs,
        result = event.result,
        layer = event.layer,
        attempt = event.attempt,
        sendStage = event.sendStage,
        connectivity = event.connectivity,
    )
}
