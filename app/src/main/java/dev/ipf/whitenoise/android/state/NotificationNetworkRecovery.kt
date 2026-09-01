package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
