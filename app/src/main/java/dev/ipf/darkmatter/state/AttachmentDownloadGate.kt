package dev.ipf.darkmatter.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounded gate for decrypted attachment fetches.
 *
 * A single visible album can contain several images/videos, and a conversation
 * can render voice notes beside them. Serializing every miss behind one permit
 * makes perceived download latency roughly the sum of all visible media fetches.
 * Keep the cap small so Blossom / FFI work is still bounded, but let the first
 * screenful of media overlap network setup and decryption.
 *
 * The legacy single-permit guard also covered a correctness failure: transient
 * FFI errors on queued-behind album tiles would leave those tiles in `failed`.
 * [withRetryingPermit] retries non-cancellation failures after releasing its
 * permit so a short-lived concurrency hiccup self-heals before the UI exposes a
 * manual retry affordance.
 */
internal class AttachmentDownloadGate(
    parallelism: Int = DEFAULT_PARALLELISM,
) {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val semaphore = Semaphore(parallelism)

    /**
     * Acquires one raw permit. Kept available for tests and for any future
     * one-shot internal callers; media downloads should normally use
     * [withRetryingPermit] so transient FFI/Blossom races can self-heal.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    suspend fun <T> withRetryingPermit(
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        initialBackoffMillis: Long = DEFAULT_INITIAL_RETRY_BACKOFF_MILLIS,
        maxBackoffMillis: Long = DEFAULT_MAX_RETRY_BACKOFF_MILLIS,
        sleep: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(initialBackoffMillis >= 0) { "initialBackoffMillis must be non-negative" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must be at least initialBackoffMillis"
        }

        var attempt = 1
        var backoffMillis = initialBackoffMillis
        while (true) {
            try {
                return withPermit(block)
            } catch (t: Throwable) {
                if (t is CancellationException || attempt >= maxAttempts) throw t
                sleep(backoffMillis)
                backoffMillis = nextRetryBackoffMillis(backoffMillis, maxBackoffMillis)
                attempt += 1
            }
        }
    }

    internal companion object {
        const val DEFAULT_PARALLELISM = 3
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_RETRY_BACKOFF_MILLIS = 150L
        const val DEFAULT_MAX_RETRY_BACKOFF_MILLIS = 600L
    }
}
