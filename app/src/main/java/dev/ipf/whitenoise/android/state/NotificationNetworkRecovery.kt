package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
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

/**
 * Retains the active diagnostic trace and only the newest coalesced successor.
 * A stale edge can never replace a newer trace, while phases from the attempt
 * already in flight remain attributable until that attempt settles.
 */
internal class NotificationNetworkRecoveryPerformanceTraces {
    private val lock = Any()
    private val traces = mutableMapOf<Long, PerformanceTrace?>()
    private val recordedPhases = mutableMapOf<Long, MutableSet<PerformancePhase>>()
    private val catchUpReadyGenerations = mutableSetOf<Long>()
    private val firstVisibleGenerations = mutableSetOf<Long>()
    private var activeGeneration: Long? = null
    private var newestGeneration = 0L

    /** Starts a generation only when it is newer than every observed edge. */
    fun begin(
        generation: Long,
        create: () -> PerformanceTrace?,
    ): Boolean =
        synchronized(lock) {
            if (generation <= newestGeneration) return@synchronized false
            newestGeneration = generation
            val active = activeGeneration
            traces.keys.removeAll { existing -> existing != active }
            recordedPhases.keys.removeAll { existing -> existing != active }
            catchUpReadyGenerations.removeAll { existing -> existing != active }
            firstVisibleGenerations.removeAll { existing -> existing != active }
            traces[generation] = create()
            true
        }

    /** Makes [generation] active and discards traces for superseded attempts. */
    fun activate(generation: Long) {
        synchronized(lock) {
            if (!traces.containsKey(generation)) return
            activeGeneration = generation
            traces.keys.removeAll { existing -> existing < generation }
            recordedPhases.keys.removeAll { existing -> existing < generation }
            catchUpReadyGenerations.removeAll { existing -> existing < generation }
            firstVisibleGenerations.removeAll { existing -> existing < generation }
        }
    }

    /** Returns the trace owned by [generation], if diagnostics were active at its edge. */
    fun forGeneration(generation: Long): PerformanceTrace? = synchronized(lock) { traces[generation] }

    /** Returns the generation whose recovery attempt currently owns downstream attribution. */
    fun activeGeneration(): Long? = synchronized(lock) { activeGeneration }

    /** Claims a one-shot downstream phase for [generation]. */
    fun claimPhase(
        generation: Long,
        phase: PerformancePhase,
    ): Boolean =
        synchronized(lock) {
            if (activeGeneration != generation || !traces.containsKey(generation)) return@synchronized false
            recordedPhases.getOrPut(generation) { mutableSetOf() }.add(phase)
        }

    /** Marks the native catch-up boundary and releases a generation already rendered. */
    fun markCatchUpReady(generation: Long) {
        synchronized(lock) {
            if (!traces.containsKey(generation)) return
            catchUpReadyGenerations += generation
            completeIfFinished(generation)
        }
    }

    /** Marks the first rendered frame and releases a generation already caught up. */
    fun markFirstVisible(generation: Long) {
        synchronized(lock) {
            if (!traces.containsKey(generation)) return
            firstVisibleGenerations += generation
            completeIfFinished(generation)
        }
    }

    private fun completeIfFinished(generation: Long) {
        if (generation in catchUpReadyGenerations && generation in firstVisibleGenerations) {
            release(generation)
        }
    }

    private fun release(generation: Long) {
        traces.remove(generation)
        recordedPhases.remove(generation)
        catchUpReadyGenerations.remove(generation)
        firstVisibleGenerations.remove(generation)
        if (activeGeneration == generation) activeGeneration = null
    }
}

/** One privacy-safe in-memory recovery marker used by device acceptance tests. */
internal data class NotificationNetworkRecoverySample(
    val generation: Long,
    val phase: PerformancePhase,
    val elapsedMillis: Long,
    val result: PerformanceResult,
    val layer: PerformanceLayer,
    val attempt: Int?,
    val count: Int?,
)

/**
 * Joins Android, MDK-return, projection, and Compose markers under one opaque
 * generation. The bounded samples contain only enum values and numbers.
 */
@Suppress("TooManyFunctions") // Typed phase entry points keep callers from constructing invalid samples.
internal class NotificationNetworkRecoveryDiagnostics(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val traceFactory: () -> PerformanceTrace? = {
        PerformanceDiagnostics.begin(PerformanceOperation.SYNC_CATCH_UP)
    },
    private val traceRecorder: (
        PerformanceTrace?,
        PerformancePhase,
        Long,
        PerformanceResult,
        PerformanceLayer,
        Int?,
        Int?,
    ) -> Unit = { trace, phase, elapsed, result, layer, attempt, count ->
        PerformanceDiagnostics.record(
            trace = trace,
            phase = phase,
            elapsedMs = elapsed,
            result = result,
            layer = layer,
            attempt = attempt,
            count = count,
        )
    },
) {
    private val traces = NotificationNetworkRecoveryPerformanceTraces()
    private val sampleLock = Any()
    private val recentSamples = ArrayDeque<NotificationNetworkRecoverySample>()

    /** Starts a trace for a fresh validated offline-to-online edge. */
    fun networkRestored(generation: Long) {
        if (!traces.begin(generation, traceFactory)) return
        record(
            generation = generation,
            phase = PerformancePhase.NETWORK_RESTORED,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.ANDROID,
        )
    }

    /** Makes [generation] the owner before its next retry attempt begins. */
    fun attemptStarted(
        generation: Long,
        attempt: Int,
    ) {
        traces.activate(generation)
        record(
            generation = generation,
            phase = PerformancePhase.RECOVERY_ATTEMPT,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.ANDROID,
            attempt = attempt,
        )
    }

    /** Records a repeatable attempt phase such as receiver readiness or retry. */
    fun attemptPhase(
        generation: Long,
        phase: PerformancePhase,
        result: PerformanceResult,
        layer: PerformanceLayer,
        attempt: Int,
    ) {
        record(generation, phase, result, layer, attempt)
    }

    /**
     * Records the ordered guarantees established by a successful MDK return.
     * Catch-up cannot return before activation, current replay drain, durable
     * checkpoint, and projection publication have completed in MDK.
     */
    fun catchUpSucceeded(
        generation: Long,
        attempt: Int,
    ) {
        record(
            generation,
            PerformancePhase.ACCOUNT_SUBSCRIPTION_ACTIVATED,
            PerformanceResult.SUCCESS,
            PerformanceLayer.TRANSPORT,
            attempt,
        )
        record(
            generation,
            PerformancePhase.CURRENT_REPLAY_COMPLETE,
            PerformanceResult.SUCCESS,
            PerformanceLayer.TRANSPORT,
            attempt,
        )
        record(
            generation,
            PerformancePhase.DURABLE_INGEST_READY,
            PerformanceResult.SUCCESS,
            PerformanceLayer.STORAGE,
            attempt,
        )
        record(
            generation,
            PerformancePhase.ACCOUNT_CATCH_UP_READY,
            PerformanceResult.SUCCESS,
            PerformanceLayer.MDK,
            attempt,
        )
        traces.markCatchUpReady(generation)
    }

    /** Attributes the first chat-list subscription update after recovery. */
    fun chatListSubscriptionReceived(count: Int = 1): Long? =
        recordCurrentOnce(
            phase = PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED,
            layer = PerformanceLayer.ANDROID,
            count = count,
        )

    /** Attributes the first timeline subscription update after recovery. */
    fun timelineSubscriptionReceived(count: Int = 1): Long? =
        recordCurrentOnce(
            phase = PerformancePhase.TIMELINE_SUBSCRIPTION_RECEIVED,
            layer = PerformanceLayer.ANDROID,
            count = count,
        )

    /** Records publication of the authoritative chat-list projection. */
    fun chatListProjectionPublished(
        generation: Long,
        count: Int,
    ): Boolean =
        recordOnce(
            generation = generation,
            phase = PerformancePhase.CHAT_LIST_PROJECTION_PUBLISHED,
            layer = PerformanceLayer.ANDROID,
            count = count,
        )

    /** Records publication of the authoritative timeline projection. */
    fun timelineProjectionPublished(
        generation: Long,
        count: Int,
    ): Boolean =
        recordOnce(
            generation = generation,
            phase = PerformancePhase.TIMELINE_PROJECTION_PUBLISHED,
            layer = PerformanceLayer.ANDROID,
            count = count,
        )

    /** Records the first Compose frame containing a recovered projection. */
    fun firstVisibleFrame(generation: Long): Boolean {
        val recorded =
            recordOnce(
                generation = generation,
                phase = PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME,
                layer = PerformanceLayer.ANDROID,
            )
        if (recorded) traces.markFirstVisible(generation)
        return recorded
    }

    /** Returns a bounded numeric-only snapshot for an instrumented report. */
    fun samples(): List<NotificationNetworkRecoverySample> = synchronized(sampleLock) { recentSamples.toList() }

    private fun recordCurrentOnce(
        phase: PerformancePhase,
        layer: PerformanceLayer,
        count: Int,
    ): Long? {
        val generation = traces.activeGeneration() ?: return null
        return generation.takeIf { recordOnce(it, phase, layer, count) }
    }

    private fun recordOnce(
        generation: Long,
        phase: PerformancePhase,
        layer: PerformanceLayer,
        count: Int? = null,
    ): Boolean {
        if (!traces.claimPhase(generation, phase)) return false
        record(generation, phase, PerformanceResult.SUCCESS, layer, count = count)
        return true
    }

    private fun record(
        generation: Long,
        phase: PerformancePhase,
        result: PerformanceResult,
        layer: PerformanceLayer,
        attempt: Int? = null,
        count: Int? = null,
    ) {
        val trace = traces.forGeneration(generation)
        val startedAt = trace?.startedAtMs ?: nowMillis()
        val elapsed = (nowMillis() - startedAt).coerceAtLeast(0L)
        val sample = NotificationNetworkRecoverySample(generation, phase, elapsed, result, layer, attempt, count)
        synchronized(sampleLock) {
            recentSamples.addLast(sample)
            while (recentSamples.size > RECOVERY_SAMPLE_LIMIT) recentSamples.removeFirst()
        }
        traceRecorder(trace, phase, elapsed, result, layer, attempt, count)
    }

    private companion object {
        const val RECOVERY_SAMPLE_LIMIT = 512
    }
}

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
@Suppress("TooManyFunctions") // Recovery lifecycle and typed diagnostic forwarding form one ownership boundary.
internal class NotificationNetworkRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val shouldContinue: () -> Boolean,
    private val wakeDurableOutbound: suspend () -> Boolean,
    private val ensureNotificationReceiverActive: suspend () -> Boolean,
    private val catchUpAccounts: suspend () -> Boolean,
    private val awaitRetry: suspend (generation: Long, attempt: Int) -> Unit,
    private val onDrainCompleted: () -> Unit,
    private val diagnostics: NotificationNetworkRecoveryDiagnostics = NotificationNetworkRecoveryDiagnostics(),
) {
    private val job = NotificationJobSlot()
    private val requestedGeneration = AtomicLong(0L)
    private val completedGeneration = AtomicLong(0L)

    /** Retains and schedules the newest validated offline-to-online edge. */
    fun noteNetworkRestored(generation: Long) {
        diagnostics.networkRestored(generation)
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

    /** Attributes the first Android chat-list update to the active recovery. */
    fun recordChatListSubscriptionReceived(count: Int = 1): Long? = diagnostics.chatListSubscriptionReceived(count)

    /** Attributes the first Android timeline update to the active recovery. */
    fun recordTimelineSubscriptionReceived(count: Int = 1): Long? = diagnostics.timelineSubscriptionReceived(count)

    /** Records the chat-list projection publication for a captured generation. */
    fun recordChatListProjectionPublished(
        generation: Long,
        count: Int,
    ): Boolean = diagnostics.chatListProjectionPublished(generation, count)

    /** Records the timeline projection publication for a captured generation. */
    fun recordTimelineProjectionPublished(
        generation: Long,
        count: Int,
    ): Boolean = diagnostics.timelineProjectionPublished(generation, count)

    /** Records a recovered projection's first committed Compose frame. */
    fun recordFirstVisibleFrame(generation: Long): Boolean = diagnostics.firstVisibleFrame(generation)

    /** Returns numeric-only recovery samples for the instrumented latency report. */
    fun performanceSamples(): List<NotificationNetworkRecoverySample> = diagnostics.samples()

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
        recordAttemptStart(generation, attempt)
        return runNotificationReconnectOnNetworkRestore(
            wakeDurableOutbound = {
                val succeeded = wakeDurableOutbound()
                diagnostics.attemptPhase(
                    generation = generation,
                    phase = PerformancePhase.CONNECTIVITY_WAKE_READY,
                    result = if (succeeded) PerformanceResult.SUCCESS else PerformanceResult.FAILURE,
                    layer = PerformanceLayer.MDK,
                    attempt = attempt,
                )
            },
            ensureNotificationReceiverActive = {
                val ready = ensureNotificationReceiverActive()
                diagnostics.attemptPhase(
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
                diagnostics.attemptPhase(
                    generation = generation,
                    phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
                    result = PerformanceResult.PENDING,
                    layer = PerformanceLayer.MDK,
                    attempt = attempt,
                )
                val succeeded = catchUpAccounts()
                if (succeeded) {
                    diagnostics.catchUpSucceeded(generation, attempt)
                } else {
                    diagnostics.attemptPhase(
                        generation = generation,
                        phase = PerformancePhase.ACCOUNT_CATCH_UP_RETRY,
                        result = PerformanceResult.PENDING,
                        layer = PerformanceLayer.MDK,
                        attempt = attempt,
                    )
                }
                succeeded
            },
        )
    }

    /** Activates the generation trace before recording its next retry attempt. */
    private fun recordAttemptStart(
        generation: Long,
        attempt: Int,
    ) {
        diagnostics.attemptStarted(generation, attempt)
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
