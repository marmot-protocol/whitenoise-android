package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace

/** Closed reasons only: no token, payload, identity, relay, content or exception text. */
internal enum class PushWakeEvent(
    val phase: PerformancePhase,
) {
    ReceivedHigh(PerformancePhase.PUSH_RECEIVED_HIGH),
    ReceivedNormal(PerformancePhase.PUSH_RECEIVED_NORMAL),
    ReceivedUnknown(PerformancePhase.PUSH_RECEIVED_UNKNOWN),
    OriginalHigh(PerformancePhase.PUSH_ORIGINAL_HIGH),
    OriginalNormal(PerformancePhase.PUSH_ORIGINAL_NORMAL),
    OriginalUnknown(PerformancePhase.PUSH_ORIGINAL_UNKNOWN),
    Deleted(PerformancePhase.PUSH_DELETED),
    OwnerAccepted(PerformancePhase.PUSH_OWNER_ACCEPTED),
    ServiceAccepted(PerformancePhase.PUSH_SERVICE_ACCEPTED),
    ServiceStarted(PerformancePhase.PUSH_SERVICE_STARTED),
    Scheduled(PerformancePhase.PUSH_SCHEDULED),
    ScheduleFailed(PerformancePhase.PUSH_SCHEDULE_FAILED),
    PersistenceFailed(PerformancePhase.PUSH_PERSISTENCE_FAILED),
    WorkerStarted(PerformancePhase.PUSH_WORKER_STARTED),
    AttemptStarted(PerformancePhase.PUSH_ATTEMPT_STARTED),
    AttemptFailed(PerformancePhase.PUSH_ATTEMPT_FAILED),
    AttemptSucceeded(PerformancePhase.PUSH_ATTEMPT_SUCCEEDED),
    Candidate(PerformancePhase.PUSH_CANDIDATE),
    Posted(PerformancePhase.PUSH_POSTED),
    Suppressed(PerformancePhase.PUSH_SUPPRESSED),
    Incomplete(PerformancePhase.PUSH_INCOMPLETE),
}

private data class PushCallbackTrace(
    val trace: PerformanceTrace,
    val startedAtMs: Long,
    var reachedPostingOutcome: Boolean = false,
)

/** Reuses the opt-in, 30-minute/256-event local sink with one anonymous trace per callback. */
internal object PushWakeDiagnostics {
    private const val CORRELATION_WINDOW_MS = 30 * 60 * 1_000L
    private const val MAX_ACTIVE_CALLBACKS = 32
    private val failureEvents =
        setOf(
            PushWakeEvent.AttemptFailed,
            PushWakeEvent.ScheduleFailed,
            PushWakeEvent.PersistenceFailed,
        )
    private val activeCallbacks = ArrayDeque<PushCallbackTrace>()
    private var attemptCallbacks: Set<PushCallbackTrace> = emptySet()

    /** Starts a coalesced episode trace and records received/original priorities separately. */
    @Synchronized
    fun received(
        priority: PushWakePriority,
        original: PushWakePriority,
        deleted: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        pruneExpired(now)
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.PUSH_RECOVERY) ?: return
        if (activeCallbacks.size == MAX_ACTIVE_CALLBACKS) {
            val expired = activeCallbacks.removeFirst()
            attemptCallbacks = attemptCallbacks - expired
            expired.record(PushWakeEvent.Incomplete, now, activeCallbacks.size)
        }
        val callback = PushCallbackTrace(trace = trace, startedAtMs = now)
        activeCallbacks.addLast(callback)
        callback.record(
            if (deleted) {
                PushWakeEvent.Deleted
            } else {
                when (priority) {
                    PushWakePriority.High -> PushWakeEvent.ReceivedHigh
                    PushWakePriority.Normal -> PushWakeEvent.ReceivedNormal
                    PushWakePriority.Unknown -> PushWakeEvent.ReceivedUnknown
                }
            },
            now,
            activeCallbacks.size,
        )
        callback.record(
            when (original) {
                PushWakePriority.High -> PushWakeEvent.OriginalHigh
                PushWakePriority.Normal -> PushWakeEvent.OriginalNormal
                PushWakePriority.Unknown -> PushWakeEvent.OriginalUnknown
            },
            now,
            activeCallbacks.size,
        )
    }

    /** Completes the coalesced set; callbacks without a posting outcome retain an explicit incomplete phase. */
    @Synchronized
    fun complete() {
        val now = SystemClock.elapsedRealtime()
        activeCallbacks.forEach { callback ->
            if (!callback.reachedPostingOutcome) {
                callback.record(PushWakeEvent.Incomplete, now, activeCallbacks.size)
            }
        }
        activeCallbacks.clear()
        attemptCallbacks = emptySet()
    }

    /** Captures attempt membership once, then emits completion only to that immutable work batch. */
    @Synchronized
    fun event(event: PushWakeEvent) {
        val now = SystemClock.elapsedRealtime()
        pruneExpired(now)
        if (event == PushWakeEvent.AttemptStarted) {
            attemptCallbacks = activeCallbacks.toSet()
        } else if (event in attemptTerminalEvents && attemptCallbacks.isEmpty()) {
            // Supervision can fail before the runtime reaches AttemptStarted. In that case the callbacks
            // already waiting at the terminal edge own the failure; an established attempt snapshot is
            // never widened, so callbacks arriving during real work cannot inherit its completion.
            attemptCallbacks = activeCallbacks.toSet()
        }
        val recipients =
            if (event in attemptScopedEvents) {
                attemptCallbacks
            } else {
                activeCallbacks.lastOrNull()?.let(::setOf).orEmpty()
            }
        recipients.forEach { callback ->
            callback.record(event, now, activeCallbacks.size)
            if (event == PushWakeEvent.Posted || event == PushWakeEvent.Suppressed) {
                callback.reachedPostingOutcome = true
            }
        }
    }

    private val attemptScopedEvents =
        setOf(
            PushWakeEvent.AttemptStarted,
            PushWakeEvent.AttemptFailed,
            PushWakeEvent.AttemptSucceeded,
            PushWakeEvent.Candidate,
            PushWakeEvent.Posted,
            PushWakeEvent.Suppressed,
        )

    private val attemptTerminalEvents =
        setOf(
            PushWakeEvent.AttemptFailed,
            PushWakeEvent.AttemptSucceeded,
        )

    /** Expires callback ownership only on an existing diagnostic edge; no timer or idle wake is introduced. */
    private fun pruneExpired(now: Long) {
        while (activeCallbacks.firstOrNull()?.let { now - it.startedAtMs >= CORRELATION_WINDOW_MS } == true) {
            val expired = activeCallbacks.removeFirst()
            attemptCallbacks = attemptCallbacks - expired
            expired.record(PushWakeEvent.Incomplete, now, activeCallbacks.size + 1)
        }
    }

    /** Writes one closed event on this callback's own process-local recovery token. */
    private fun PushCallbackTrace.record(
        event: PushWakeEvent,
        now: Long,
        callbackCount: Int,
    ) {
        PerformanceDiagnostics.record(
            trace = trace,
            phase = event.phase,
            elapsedMs = (now - startedAtMs).coerceAtLeast(0L),
            count = callbackCount,
            result =
                when {
                    event == PushWakeEvent.Incomplete -> PerformanceResult.PENDING
                    event in failureEvents -> PerformanceResult.FAILURE
                    else -> PerformanceResult.SUCCESS
                },
        )
    }
}
