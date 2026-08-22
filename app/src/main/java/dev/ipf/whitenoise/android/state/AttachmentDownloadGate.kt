package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal enum class AttachmentDownloadPriority {
    Automatic,
    Interactive,
}

/** Signals that Stop automatic downloads removed this request before admission. */
internal class AutomaticBacklogStoppedException : CancellationException("automatic attachment backlog stopped")

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

    private class Waiter(
        val key: String,
        val accountRef: String?,
        var priority: AttachmentDownloadPriority,
        val admitted: CompletableDeferred<Unit> = CompletableDeferred(),
        var ownsPermit: Boolean = false,
    )

    /** Tracks stale replacements while enforcing one permit owner per caller key. */
    private class KeyedWaiters {
        private val entries = mutableMapOf<String, MutableList<Waiter>>()

        fun forKey(key: String): List<Waiter>? = entries[key]

        fun register(waiter: Waiter) {
            entries.getOrPut(waiter.key) { mutableListOf() }.add(waiter)
        }

        fun unregister(waiter: Waiter) {
            val waiters = entries[waiter.key] ?: return
            waiters.removeAll { it === waiter }
            if (waiters.isEmpty()) entries.remove(waiter.key)
        }

        fun canOwnPermit(waiter: Waiter): Boolean =
            entries[waiter.key]
                .orEmpty()
                .none { sibling -> sibling !== waiter && sibling.ownsPermit }
    }

    private val lock = Any()
    private val automatic = ArrayDeque<Waiter>()
    private val interactive = ArrayDeque<Waiter>()
    private val keyedWaiters = KeyedWaiters()
    private val anonymousKey = AtomicLong()
    private var activePermits = 0
    private var consecutiveInteractiveAdmissions = 0
    private val maxPermits = parallelism

    /**
     * Acquires one raw permit. Kept available for tests and for any future
     * one-shot internal callers; media downloads should normally use
     * [withRetryingPermit] so transient FFI/Blossom races can self-heal.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T =
        withPermit(
            key = "anonymous-${anonymousKey.incrementAndGet()}",
            accountRef = null,
            priority = AttachmentDownloadPriority.Automatic,
            block = block,
        )

    suspend fun <T> withPermit(
        key: String,
        accountRef: String?,
        priority: AttachmentDownloadPriority,
        block: suspend () -> T,
    ): T {
        val waiter = acquire(key, accountRef, priority)
        try {
            return block()
        } finally {
            release(waiter)
        }
    }

    /** Moves a queued automatic identity to the interactive lane without creating another waiter. */
    fun promote(key: String): Boolean =
        synchronized(lock) {
            val waiters = keyedWaiters.forKey(key) ?: return@synchronized false
            waiters
                .filter { !it.ownsPermit && it.priority == AttachmentDownloadPriority.Automatic }
                .forEach { waiter ->
                    automatic.remove(waiter)
                    waiter.priority = AttachmentDownloadPriority.Interactive
                    interactive.addLast(waiter)
                }
            true
        }

    /** Cancels only automatic requests that have not acquired a permit. */
    fun cancelQueuedAutomatic(accountRef: String): Int {
        val cancelled =
            synchronized(lock) {
                automatic
                    .filter { it.accountRef == accountRef && !it.ownsPermit }
                    .also { waiters ->
                        waiters.forEach { waiter ->
                            automatic.remove(waiter)
                            keyedWaiters.unregister(waiter)
                        }
                    }
            }
        cancelled.forEach { waiter ->
            waiter.admitted.cancel(AutomaticBacklogStoppedException())
        }
        return cancelled.size
    }

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

    private suspend fun acquire(
        key: String,
        accountRef: String?,
        priority: AttachmentDownloadPriority,
    ): Waiter {
        val waiter = Waiter(key, accountRef, priority)
        val admittedImmediately =
            synchronized(lock) {
                keyedWaiters.register(waiter)
                lane(priority).addLast(waiter)
                admitAvailableLocked()
                waiter.ownsPermit
            }
        if (admittedImmediately) return waiter
        try {
            waiter.admitted.await()
            return waiter
        } catch (cancellation: CancellationException) {
            synchronized(lock) {
                if (waiter.ownsPermit) {
                    waiter.ownsPermit = false
                    activePermits -= 1
                    keyedWaiters.unregister(waiter)
                    admitAvailableLocked()
                } else {
                    lane(waiter.priority).remove(waiter)
                    keyedWaiters.unregister(waiter)
                }
            }
            throw cancellation
        }
    }

    private fun release(waiter: Waiter) {
        synchronized(lock) {
            if (!waiter.ownsPermit) return
            waiter.ownsPermit = false
            activePermits -= 1
            keyedWaiters.unregister(waiter)
            admitAvailableLocked()
        }
    }

    private fun admitAvailableLocked() {
        while (activePermits < maxPermits) {
            val next = nextWaiterLocked() ?: return
            next.ownsPermit = true
            activePermits += 1
            next.admitted.complete(Unit)
        }
    }

    private fun nextWaiterLocked(): Waiter? {
        val nextAutomatic = automatic.firstOrNull(keyedWaiters::canOwnPermit)
        val nextInteractive = interactive.firstOrNull(keyedWaiters::canOwnPermit)
        val admitAutomatic =
            nextAutomatic != null &&
                (nextInteractive == null || consecutiveInteractiveAdmissions >= MAX_INTERACTIVE_BURST)
        return if (admitAutomatic) {
            consecutiveInteractiveAdmissions = 0
            automatic.remove(nextAutomatic)
            nextAutomatic
        } else {
            nextInteractive
                ?.also {
                    interactive.remove(it)
                    consecutiveInteractiveAdmissions += 1
                } ?: nextAutomatic?.also {
                automatic.remove(it)
                consecutiveInteractiveAdmissions = 0
            }
        }
    }

    private fun lane(priority: AttachmentDownloadPriority): ArrayDeque<Waiter> =
        when (priority) {
            AttachmentDownloadPriority.Automatic -> automatic
            AttachmentDownloadPriority.Interactive -> interactive
        }

    internal companion object {
        const val DEFAULT_PARALLELISM = 3
        const val MAX_INTERACTIVE_BURST = 3
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
