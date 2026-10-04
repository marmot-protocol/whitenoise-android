package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class LocalDeleteRecoveryObserver(
    val recoverTransport: suspend () -> Unit = {},
    val onProgress: (LocalDeletePhase, Int, Boolean?, Boolean) -> Unit = { _, _, _, _ -> },
)

/**
 * A local wipe is idempotent, but a closed worker can lose the response after committing it.
 * Resolve that ambiguity against the native group projection before another mutation. A failed
 * reconciliation never authorizes another delete; the caller can surface one terminal error.
 * Each branch distinguishes a committed wipe, a safe retry, or a terminal/cancelled mutation.
 */
@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod", "ThrowsCount")
internal suspend fun deleteLocalGroupWithRecovery(
    isCurrent: () -> Boolean,
    delete: suspend () -> Unit,
    isGroupPresent: suspend () -> Boolean,
    pause: (suspend (Long) -> Unit)? = null,
    observer: LocalDeleteRecoveryObserver = LocalDeleteRecoveryObserver(),
) {
    var lastFailure: Throwable? = null
    for (attempt in 1..IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
        if (!isCurrent()) throw CancellationException("chat binding changed during local deletion")
        try {
            observer.onProgress(LocalDeletePhase.NativeDelete, attempt, null, false)
            delete()
            observer.onProgress(LocalDeletePhase.NativeDelete, attempt, false, false)
            return
        } catch (failure: Throwable) {
            rethrowIfCancellation(failure)
            if (!isRetryableIdempotentMutationError(failure)) throw failure
            lastFailure = failure
            if (isTransientRuntimeWorkerError(failure)) observer.recoverTransport()
        }

        // A committed wipe removes the durable chat-list row. A failed read leaves the
        // outcome uncertain and must not trigger another destructive call.
        var present: Boolean? = null
        var reconciliationFailure: Throwable? = null
        for (read in attempt..IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
            if (!isCurrent()) throw CancellationException("chat binding changed during local deletion")
            try {
                observer.onProgress(LocalDeletePhase.PresenceReconciliation, read, null, false)
                present = isGroupPresent()
                if (!isCurrent()) throw CancellationException("chat binding changed during local deletion read")
                observer.onProgress(LocalDeletePhase.PresenceReconciliation, read, present, false)
                break
            } catch (failure: Throwable) {
                rethrowIfCancellation(failure)
                if (!isRetryableIdempotentMutationError(failure)) throw failure
                reconciliationFailure = failure
                if (read < IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
                    if (isTransientRuntimeWorkerError(failure)) observer.recoverTransport()
                    pauseLocalGroupDeleteRetry(pause)
                }
            }
        }
        if (present == false) return
        if (present == null || attempt == IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
            if (present == null) lastFailure = reconciliationFailure ?: lastFailure
            observer.onProgress(
                if (present == null) LocalDeletePhase.PresenceReconciliation else LocalDeletePhase.NativeDelete,
                IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS,
                present,
                true,
            )
            break
        }
        pauseLocalGroupDeleteRetry(pause)
    }
    throw lastFailure ?: IllegalStateException("local deletion retry budget exhausted")
}

/** Keep production backoff out of suspend default-argument lowering; tests can supply a clock. */
internal suspend fun pauseLocalGroupDeleteRetry(pause: (suspend (Long) -> Unit)?) {
    if (pause == null) {
        delay(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
    } else {
        pause(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
    }
}
