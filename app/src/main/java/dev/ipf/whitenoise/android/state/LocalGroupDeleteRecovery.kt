package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A local wipe is idempotent, but a closed worker can lose the response after committing it.
 * Resolve that ambiguity against the native group projection before another mutation. A failed
 * reconciliation never authorizes another delete; the caller can surface one terminal error.
 */
@Suppress("TooGenericExceptionCaught") // Native errors are classified; cancellation is always propagated.
internal suspend fun deleteLocalGroupWithRecovery(
    isCurrent: () -> Boolean,
    delete: suspend () -> Unit,
    isGroupPresent: suspend () -> Boolean,
    pause: suspend (Long) -> Unit = ::delay,
) {
    var lastFailure: Throwable? = null
    for (attempt in 1..IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
        if (!isCurrent()) throw CancellationException("chat binding changed during local deletion")
        try {
            delete()
            return
        } catch (failure: Throwable) {
            rethrowIfCancellation(failure)
            if (!isRetryableIdempotentMutationError(failure)) throw failure
            lastFailure = failure
        }

        // A committed wipe removes the durable chat-list row. A failed read leaves the
        // outcome uncertain and must not trigger another destructive call.
        var present: Boolean? = null
        for (read in attempt..IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
            if (!isCurrent()) throw CancellationException("chat binding changed during local deletion")
            try {
                present = isGroupPresent()
                break
            } catch (failure: Throwable) {
                rethrowIfCancellation(failure)
                if (!isRetryableIdempotentMutationError(failure)) throw failure
                if (read < IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
                    pause(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
                }
            }
        }
        if (present == false) return
        if (present == null || attempt == IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) break
        pause(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
    }
    throw lastFailure ?: IllegalStateException("local deletion retry budget exhausted")
}
