package dev.ipf.whitenoise.android.state

import kotlin.time.TimeSource

/** Bounded retry window for idempotent account-runtime mutations. */
internal const val IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS: Int = 3
internal const val IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS: Long = 700L

/** Catch-up can own the account worker for seconds; bound both polling and elapsed retry time. */
internal const val IDEMPOTENT_CONTENTION_RETRY_ATTEMPTS: Int = 9
internal const val IDEMPOTENT_CONTENTION_RETRY_WINDOW_MS: Long = 30_000L
private const val IDEMPOTENT_CONTENTION_MAX_BACKOFF_MS: Long = 5_000L

/**
 * Allows multi-second catch-up to release the worker with at most nine calls and 29.9s of backoff.
 * The elapsed window includes native call time, but never cancels an in-flight mutation whose
 * completion could be ambiguous. Other transient failures retain the original three-call budget.
 */
@Suppress("TooGenericExceptionCaught") // FFI/runtime failures are classified; cancellation is always rethrown.
internal suspend fun <T> retryIdempotentRuntimeMutation(
    onTransientFailure: suspend (attempt: Int) -> Unit = {},
    timeSource: TimeSource = TimeSource.Monotonic,
    mutation: suspend () -> T,
): T {
    val started = timeSource.markNow()
    var lastTransient: Throwable? = null
    var contentionBackoffMs = IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS
    var attempt = 1
    // Recheck after suspension: a delayed coroutine must not restart an expired mutation.
    while (attempt == 1 || started.elapsedNow().inWholeMilliseconds < IDEMPOTENT_CONTENTION_RETRY_WINDOW_MS) {
        try {
            return mutation()
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            if (!isRetryableIdempotentMutationError(throwable)) throw throwable
            lastTransient = throwable
            val contention = isTypedMutationContention(throwable)
            val attemptLimit =
                if (contention) {
                    IDEMPOTENT_CONTENTION_RETRY_ATTEMPTS
                } else {
                    IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS
                }
            val backoffMs = if (contention) contentionBackoffMs else IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS
            val remainingMs = IDEMPOTENT_CONTENTION_RETRY_WINDOW_MS - started.elapsedNow().inWholeMilliseconds
            if (attempt >= attemptLimit || backoffMs >= remainingMs) break
            onTransientFailure(attempt)
            kotlinx.coroutines.delay(backoffMs)
            if (contention) {
                contentionBackoffMs = (contentionBackoffMs * 2).coerceAtMost(IDEMPOTENT_CONTENTION_MAX_BACKOFF_MS)
            }
            attempt += 1
        }
    }
    throw lastTransient ?: IllegalStateException("runtime mutation retry budget exhausted")
}
