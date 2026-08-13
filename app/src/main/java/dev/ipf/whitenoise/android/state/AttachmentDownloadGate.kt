package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

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
        shouldRetry: (Throwable) -> Boolean = { true },
        sleep: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> T,
    ): T {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must be at least initialBackoffMillis"
        }

        var attempt = 1
        var backoffMillis = initialBackoffMillis
        while (true) {
            try {
                return withPermit(block)
            } catch (t: Throwable) {
                if (t is CancellationException || !shouldRetry(t) || attempt >= maxAttempts) throw t
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

/**
 * Conservative classifier for the current MDK media error surface.
 *
 * MDK does not yet expose a typed Blossom/network error, so only failures that
 * clearly describe a pre-result connectivity problem are retried. Integrity,
 * policy, decryption, missing-reference, and ordinary unknown failures fail
 * immediately instead of repeating a potentially minute-long request.
 */
internal fun isTransientAttachmentDownloadFailure(throwable: Throwable): Boolean {
    val explicitlyTerminal =
        throwable is CancellationException ||
            throwable is MarmotKitException.InvalidMediaReference
    val typedTransient = throwable is MarmotKitException.StorageBusy || throwable is IOException
    val text =
        generateSequence(throwable) { it.cause }
            .joinToString(separator = "\n") { error ->
                listOfNotNull(error.message, error.javaClass.simpleName).joinToString(" ")
            }.lowercase()
    val retryableHttpStatus =
        Regex("""\bhttp\s+(408|425|429|5\d\d)\b""").containsMatchIn(text)
    return when {
        explicitlyTerminal -> false
        typedTransient -> true
        else ->
            retryableHttpStatus ||
                "request timed out" in text ||
                "connection failed" in text ||
                "connection refused" in text ||
                "connection reset" in text ||
                "dns lookup failed" in text ||
                "temporary failure in name resolution" in text
    }
}
