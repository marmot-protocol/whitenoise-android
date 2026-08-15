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
