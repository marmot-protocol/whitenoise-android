package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class NotificationRuntimeRetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 8_000L,
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMillis >= 0L)
        require(maxDelayMillis >= initialDelayMillis)
    }
}

internal sealed interface NotificationRuntimeSupervisionOutcome {
    data class Started(
        val attempts: Int,
    ) : NotificationRuntimeSupervisionOutcome

    data class Exhausted(
        val attempts: Int,
        val failureClass: String,
    ) : NotificationRuntimeSupervisionOutcome

    data class RecoveryBoundaryChanged(
        val attempts: Int,
    ) : NotificationRuntimeSupervisionOutcome
}

internal enum class NotificationRuntimeBootstrapAction {
    Continue,
    Finish,
    StopAfterExhaustion,
}

internal data class NotificationRuntimeBootstrapDecision(
    val action: NotificationRuntimeBootstrapAction,
    val completedPushWakeGeneration: Long,
    val pendingUserOwnedStart: Boolean,
    val reconcileUserOwnedFailure: Boolean = false,
)

internal data class NotificationRuntimeBootstrapSnapshot(
    val attemptedStartId: Int,
    val latestStartId: Int,
    val attemptedPushWakeGeneration: Long?,
    val pendingPushWakeGeneration: Long,
    val completedPushWakeGeneration: Long,
    val pendingNativePushRegistrationSync: Boolean,
    val pendingUserOwnedStart: Boolean,
)

/**
 * Pure transition for the service-owned bootstrap loop.
 *
 * [attemptedStartId] and [attemptedPushWakeGeneration] fence the work captured
 * before the suspending runtime attempt. A newer start or push wake therefore
 * receives its own bounded round instead of being consumed by an older
 * attempt's terminal result.
 */
internal fun decideNotificationRuntimeBootstrap(
    outcome: NotificationRuntimeSupervisionOutcome,
    snapshot: NotificationRuntimeBootstrapSnapshot,
): NotificationRuntimeBootstrapDecision =
    with(snapshot) {
        when (outcome) {
            is NotificationRuntimeSupervisionOutcome.Started -> {
                val completedGeneration =
                    attemptedPushWakeGeneration
                        ?.takeIf { it <= pendingPushWakeGeneration }
                        ?: completedPushWakeGeneration
                val workQueuedDuringAttempt =
                    latestStartId > attemptedStartId ||
                        pendingPushWakeGeneration > completedGeneration ||
                        pendingNativePushRegistrationSync
                NotificationRuntimeBootstrapDecision(
                    action =
                        if (workQueuedDuringAttempt) {
                            NotificationRuntimeBootstrapAction.Continue
                        } else {
                            NotificationRuntimeBootstrapAction.Finish
                        },
                    completedPushWakeGeneration = completedGeneration,
                    pendingUserOwnedStart = false,
                )
            }
            is NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged ->
                NotificationRuntimeBootstrapDecision(
                    action = NotificationRuntimeBootstrapAction.Finish,
                    completedPushWakeGeneration = completedPushWakeGeneration,
                    pendingUserOwnedStart = false,
                )
            is NotificationRuntimeSupervisionOutcome.Exhausted -> {
                val capturedPushWakeGeneration = attemptedPushWakeGeneration ?: completedPushWakeGeneration
                val workQueuedDuringAttempt =
                    latestStartId > attemptedStartId || pendingPushWakeGeneration > capturedPushWakeGeneration
                NotificationRuntimeBootstrapDecision(
                    action =
                        if (workQueuedDuringAttempt) {
                            NotificationRuntimeBootstrapAction.Continue
                        } else {
                            NotificationRuntimeBootstrapAction.StopAfterExhaustion
                        },
                    completedPushWakeGeneration = completedPushWakeGeneration,
                    pendingUserOwnedStart = pendingUserOwnedStart && workQueuedDuringAttempt,
                    reconcileUserOwnedFailure = pendingUserOwnedStart && !workQueuedDuringAttempt,
                )
            }
        }
    }

/**
 * Owns the bounded retry contract for a foreground-service notification-runtime bootstrap.
 *
 * The caller still owns Android service lifecycle and start-request coalescing. Keeping retry
 * progression here makes transient failure, exhaustion, cancellation, and destructive-wipe
 * fencing deterministic without constructing an Activity or persisting protocol state.
 */
internal class NotificationRuntimeSupervisor(
    private val policy: NotificationRuntimeRetryPolicy = NotificationRuntimeRetryPolicy(),
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun supervise(
        recoveryAllowed: () -> Boolean,
        startRuntime: suspend () -> Unit,
        onAttemptFailed: (attempt: Int, retryDelayMillis: Long?, failureClass: String) -> Unit = { _, _, _ -> },
    ): NotificationRuntimeSupervisionOutcome {
        var retryDelayMillis = policy.initialDelayMillis
        var attempt = 0
        var outcome: NotificationRuntimeSupervisionOutcome? = null
        while (outcome == null && attempt < policy.maxAttempts) {
            attempt += 1
            if (!recoveryAllowed()) {
                outcome = NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt - 1)
            } else {
                val failure = runCatching { startRuntime() }.exceptionOrNull()
                rethrowFatalNotificationRuntimeFailure(failure)
                if (failure == null) {
                    outcome =
                        if (recoveryAllowed()) {
                            NotificationRuntimeSupervisionOutcome.Started(attempts = attempt)
                        } else {
                            NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt)
                        }
                } else {
                    val failureClass = failure.javaClass.simpleName.ifBlank { "UnknownFailure" }
                    when {
                        !recoveryAllowed() -> {
                            outcome = NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt)
                        }
                        attempt == policy.maxAttempts -> {
                            onAttemptFailed(attempt, null, failureClass)
                            outcome =
                                NotificationRuntimeSupervisionOutcome.Exhausted(
                                    attempts = attempt,
                                    failureClass = failureClass,
                                )
                        }
                        else -> {
                            onAttemptFailed(attempt, retryDelayMillis, failureClass)
                            waitBeforeRetry(retryDelayMillis)
                            retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(policy.maxDelayMillis)
                        }
                    }
                }
            }
        }
        return checkNotNull(outcome)
    }
}

private fun rethrowFatalNotificationRuntimeFailure(failure: Throwable?) {
    when (failure) {
        is CancellationException -> throw failure
        is VirtualMachineError,
        is LinkageError,
        is ThreadDeath,
        -> throw failure
        else -> Unit
    }
}
