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
        for (attempt in 1..policy.maxAttempts) {
            if (!recoveryAllowed()) {
                return NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt - 1)
            }
            try {
                startRuntime()
                if (!recoveryAllowed()) {
                    return NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt)
                }
                return NotificationRuntimeSupervisionOutcome.Started(attempts = attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is LinkageError || failure is ThreadDeath) {
                    throw failure
                }
                val failureClass = failure.javaClass.simpleName.ifBlank { "UnknownFailure" }
                if (!recoveryAllowed()) {
                    return NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = attempt)
                }
                if (attempt == policy.maxAttempts) {
                    onAttemptFailed(attempt, null, failureClass)
                    return NotificationRuntimeSupervisionOutcome.Exhausted(
                        attempts = attempt,
                        failureClass = failureClass,
                    )
                }
                onAttemptFailed(attempt, retryDelayMillis, failureClass)
                waitBeforeRetry(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(policy.maxDelayMillis)
            }
        }
        error("unreachable notification-runtime supervision state")
    }
}
