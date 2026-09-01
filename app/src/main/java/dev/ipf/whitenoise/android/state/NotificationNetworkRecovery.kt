package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val NETWORK_RECOVERY_INITIAL_RETRY_DELAY_MS = 500L
private const val NETWORK_RECOVERY_MAX_RETRY_DELAY_MS = 8_000L
private const val NETWORK_RECOVERY_MAX_RETRY_DOUBLINGS = 63

/** Associates one opaque diagnostic operation with one Android recovery edge. */
internal data class NotificationNetworkRecoveryPerformanceTrace(
    val generation: Long,
    val trace: dev.ipf.whitenoise.android.diagnostics.PerformanceTrace,
)

/** Result of one validated-network recovery attempt. */
internal enum class NotificationNetworkRecoveryOutcome {
    Success,
    ReceiverUnavailable,
    CatchUpFailed,
}

/**
 * Owns the coalesced reconnect job, retained generations, retry policy, and
 * privacy-safe Android phase trace for validated network recovery.
 */
internal class NotificationNetworkRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val shouldContinue: () -> Boolean,
    private val wakeDurableOutbound: suspend () -> Boolean,
    private val ensureNotificationReceiverActive: suspend () -> Boolean,
    private val catchUpAccounts: suspend () -> Boolean,
    private val awaitRetry: suspend (generation: Long, attempt: Int) -> Unit,
    private val onDrainCompleted: () -> Unit,
) {
    private val job = NotificationJobSlot()
    private val requestedGeneration = AtomicLong(0L)
    private val completedGeneration = AtomicLong(0L)
    private val traceLock = Any()
    private var performanceTrace: NotificationNetworkRecoveryPerformanceTrace? = null

    /** Retains and schedules the newest validated offline-to-online edge. */
    fun noteNetworkRestored(generation: Long) {
        beginTrace(generation)
        requestedGeneration.accumulateAndGet(generation, ::maxOf)
        schedule()
    }

    /** Resumes retained recovery after a temporary lifecycle suppression ends. */
    fun resumeIfPending() {
        if (shouldContinue() && requestedGeneration.get() > completedGeneration.get()) schedule()
    }

    /** Reports whether a recovery drain currently owns the reconnect slot. */
    fun isActive(): Boolean = job.isActive()

    /** Cancels and joins the active drain while retaining its requested generation. */
    suspend fun cancelAndJoin() = job.cancelAndJoin()

    /** Starts one drain and guarantees a follow-up for any newer retained edge. */
    private fun schedule() {
        if (!shouldContinue()) return
        job.startIfInactive {
            val reconnectJob =
                scope.launch {
                    if (!shouldContinue()) return@launch
                    drainNotificationNetworkRecovery(
                        shouldContinue = shouldContinue,
                        requestedGeneration = requestedGeneration::get,
                        completedGeneration = completedGeneration::get,
                        runAttempt = ::runAttempt,
                        markCompleted = { generation ->
                            completedGeneration.accumulateAndGet(generation, ::maxOf)
                        },
                        awaitRetry = awaitRetry,
                    )
                }
            reconnectJob.invokeOnCompletion { cause ->
                if (cause == null) {
                    resumeIfPending()
                    onDrainCompleted()
                }
            }
            reconnectJob
        }
    }

    /** Runs one receiver-gated catch-up attempt and records its typed phases. */
    private suspend fun runAttempt(
        generation: Long,
        attempt: Int,
    ): NotificationNetworkRecoveryOutcome {
        recordPhase(
            generation = generation,
            phase = PerformancePhase.RECOVERY_ATTEMPT,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.ANDROID,
            attempt = attempt,
        )
        return runNotificationReconnectOnNetworkRestore(
            wakeDurableOutbound = {
                val succeeded = wakeDurableOutbound()
                recordPhase(
                    generation = generation,
                    phase = PerformancePhase.CONNECTIVITY_WAKE_READY,
                    result = if (succeeded) PerformanceResult.SUCCESS else PerformanceResult.FAILURE,
                    layer = PerformanceLayer.MDK,
                    attempt = attempt,
                )
            },
            ensureNotificationReceiverActive = {
                val ready = ensureNotificationReceiverActive()
                recordPhase(
                    generation = generation,
                    phase =
                        if (ready) {
                            PerformancePhase.NOTIFICATION_RECEIVER_READY
                        } else {
                            PerformancePhase.NOTIFICATION_RECEIVER_RETRY
                        },
                    result = if (ready) PerformanceResult.SUCCESS else PerformanceResult.PENDING,
                    layer = PerformanceLayer.ANDROID,
                    attempt = attempt,
                )
                ready
            },
            catchUpAccounts = {
                recordPhase(
                    generation = generation,
                    phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.MDK,
                    attempt = attempt,
                )
                val succeeded = catchUpAccounts()
                recordPhase(
                    generation = generation,
                    phase =
                        if (succeeded) {
                            PerformancePhase.ACCOUNT_CATCH_UP_READY
                        } else {
                            PerformancePhase.ACCOUNT_CATCH_UP_RETRY
                        },
                    result = if (succeeded) PerformanceResult.SUCCESS else PerformanceResult.PENDING,
                    layer = PerformanceLayer.MDK,
                    attempt = attempt,
                )
                succeeded
            },
        )
    }

    /** Starts a fresh process-local diagnostic trace for the supplied edge. */
    private fun beginTrace(generation: Long) {
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.SYNC_CATCH_UP)
        synchronized(traceLock) {
            performanceTrace = trace?.let { NotificationNetworkRecoveryPerformanceTrace(generation, it) }
        }
        trace?.let {
            PerformanceDiagnostics.record(
                trace = it,
                phase = PerformancePhase.NETWORK_RESTORED,
                elapsedMs = 0L,
                result = PerformanceResult.PENDING,
            )
        }
    }

    /** Records a phase only while its trace still represents the same edge. */
    private fun recordPhase(
        generation: Long,
        phase: PerformancePhase,
        result: PerformanceResult,
        layer: PerformanceLayer,
        attempt: Int,
    ) {
        val state =
            synchronized(traceLock) {
                performanceTrace?.takeIf { it.generation == generation }
            } ?: return
        PerformanceDiagnostics.record(
            trace = state.trace,
            phase = phase,
            elapsedMs = (SystemClock.elapsedRealtime() - state.trace.startedAtMs).coerceAtLeast(0L),
            result = result,
            layer = layer,
            attempt = attempt,
        )
    }
}

/**
 * Starts the durable outbound wake and notification-receiver recovery together,
 * then starts inbound catch-up as soon as the receiver is ready. The unrelated
 * outbound wake remains concurrent, so Android adds no second prerequisite
 * before requesting replay.
 */
internal suspend fun runNotificationReconnectOnNetworkRestore(
    wakeDurableOutbound: suspend () -> Unit,
    ensureNotificationReceiverActive: suspend () -> Boolean,
    catchUpAccounts: suspend () -> Boolean,
): NotificationNetworkRecoveryOutcome =
    coroutineScope {
        val outboundWake = async(start = CoroutineStart.UNDISPATCHED) { wakeDurableOutbound() }
        val receiverReady = async(start = CoroutineStart.UNDISPATCHED) { ensureNotificationReceiverActive() }

        val ready = receiverReady.await()
        if (!ready) {
            outboundWake.await()
            NotificationNetworkRecoveryOutcome.ReceiverUnavailable
        } else {
            val catchUp = async(start = CoroutineStart.UNDISPATCHED) { catchUpAccounts() }
            val caughtUp = catchUp.await()
            outboundWake.await()
            if (caughtUp) {
                NotificationNetworkRecoveryOutcome.Success
            } else {
                NotificationNetworkRecoveryOutcome.CatchUpFailed
            }
        }
    }

/**
 * Drains the newest requested recovery generation and retains it until catch-up
 * succeeds. Newer connectivity edges coalesce to the latest generation.
 */
internal suspend fun drainNotificationNetworkRecovery(
    shouldContinue: () -> Boolean,
    requestedGeneration: () -> Long,
    completedGeneration: () -> Long,
    runAttempt: suspend (generation: Long, attempt: Int) -> NotificationNetworkRecoveryOutcome,
    markCompleted: (generation: Long) -> Unit,
    awaitRetry: suspend (generation: Long, attempt: Int) -> Unit,
) {
    var attempt = 1
    var attemptGeneration: Long? = null
    while (currentCoroutineContext().isActive && shouldContinue()) {
        val generation = requestedGeneration()
        if (generation <= completedGeneration()) return
        if (generation != attemptGeneration) {
            attemptGeneration = generation
            attempt = 1
        }
        when (runAttempt(generation, attempt)) {
            NotificationNetworkRecoveryOutcome.Success -> {
                markCompleted(generation)
                attemptGeneration = null
                attempt = 1
            }
            NotificationNetworkRecoveryOutcome.ReceiverUnavailable,
            NotificationNetworkRecoveryOutcome.CatchUpFailed,
            -> {
                awaitRetry(generation, attempt)
                attempt = (attempt + 1).coerceAtMost(Int.MAX_VALUE)
            }
        }
    }
}

/** Exponential retry delay for a retained validated-network recovery edge. */
internal fun notificationNetworkRecoveryRetryDelayMillis(attempt: Int): Long {
    var delayMillis = NETWORK_RECOVERY_INITIAL_RETRY_DELAY_MS
    repeat((attempt - 1).coerceIn(0, NETWORK_RECOVERY_MAX_RETRY_DOUBLINGS)) {
        delayMillis = nextRetryBackoffMillis(delayMillis, NETWORK_RECOVERY_MAX_RETRY_DELAY_MS)
    }
    return delayMillis
}
