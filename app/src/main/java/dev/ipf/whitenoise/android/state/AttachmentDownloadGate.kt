package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.isMediaReferenceRefusal
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
    private val pendingPromotions = mutableSetOf<String>()
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

    /**
     * Runs [block] while holding one permit for [key]. A [foreground] automatic request, one a visible bubble made,
     * queues ahead of every other automatic request, the newest first, as MDK orders its own automatic queue.
     * Background work queues behind them in arrival order.
     */
    suspend fun <T> withPermit(
        key: String,
        accountRef: String?,
        priority: AttachmentDownloadPriority,
        foreground: Boolean = false,
        block: suspend () -> T,
    ): T {
        val waiter = acquire(key, accountRef, priority, foreground)
        try {
            return block()
        } finally {
            release(waiter)
        }
    }

    /**
     * Moves a queued automatic identity to the interactive lane without creating another waiter. An [explicit]
     * promotion that wins the race with gate registration is retained until that key registers.
     *
     * A non-[explicit] call, for a visible bubble that asks again for work it joined, only moves the queued request to
     * the front of the automatic queue, never ahead of explicit requests, and reports whether anything was queued.
     */
    fun promote(
        key: String,
        explicit: Boolean = true,
    ): Boolean =
        synchronized(lock) {
            val waiters =
                keyedWaiters.forKey(key)
                    ?: run {
                        if (explicit) pendingPromotions.add(key)
                        return@synchronized explicit
                    }
            val queued = waiters.filter { !it.ownsPermit && it.priority == AttachmentDownloadPriority.Automatic }
            queued.forEach { waiter ->
                automatic.remove(waiter)
                if (explicit) {
                    waiter.priority = AttachmentDownloadPriority.Interactive
                    interactive.addLast(waiter)
                } else {
                    automatic.addFirst(waiter)
                }
            }
            explicit || queued.isNotEmpty()
        }

    /** True only while this attachment currently owns one of the bounded native-work permits. */
    fun isAdmitted(key: String): Boolean =
        synchronized(lock) {
            keyedWaiters.forKey(key).orEmpty().any(Waiter::ownsPermit)
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
        foreground: Boolean,
    ): Waiter {
        val waiter =
            synchronized(lock) {
                val promotedBeforeRegistration = pendingPromotions.remove(key)
                val effectivePriority =
                    if (
                        priority == AttachmentDownloadPriority.Interactive ||
                        promotedBeforeRegistration
                    ) {
                        AttachmentDownloadPriority.Interactive
                    } else {
                        AttachmentDownloadPriority.Automatic
                    }
                val registered = Waiter(key, accountRef, effectivePriority)
                keyedWaiters.register(registered)
                if (foreground && effectivePriority == AttachmentDownloadPriority.Automatic) {
                    automatic.addFirst(registered)
                } else {
                    lane(effectivePriority).addLast(registered)
                }
                admitAvailableLocked()
                registered
            }
        val admittedImmediately = waiter.ownsPermit
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
                    consecutiveInteractiveAdmissions =
                        if (nextAutomatic == null) {
                            0
                        } else {
                            consecutiveInteractiveAdmissions + 1
                        }
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
        /** MarmotKit's own attachment transfer limit; under host-managed mode this gate decides what reaches it. */
        const val DEFAULT_PARALLELISM = 4
        const val MAX_INTERACTIVE_BURST = 3
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_RETRY_BACKOFF_MILLIS = 150L
        const val DEFAULT_MAX_RETRY_BACKOFF_MILLIS = 600L
    }
}

/**
 * Conservative classifier for the current MDK media error surface.
 *
 * MDK types malformed references and policy refusals but folds transport,
 * integrity and decryption failures into one `MediaDownloadFailed`, so only
 * failures that clearly describe a pre-result connectivity problem are retried.
 * Rejected or unfetchable references, integrity, decryption and ordinary unknown
 * failures fail immediately instead of repeating a potentially minute-long request.
 */
internal fun isTransientAttachmentDownloadFailure(throwable: Throwable): Boolean {
    val explicitlyTerminal = isTerminalAttachmentDownloadFailure(throwable)
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

/** Typed contract failures and native terminal states cannot be repaired by a host transport retry. */
private fun isTerminalAttachmentDownloadFailure(failure: Throwable): Boolean =
    failure.isMediaReferenceRefusal() ||
        when (failure) {
            is CancellationException,
            is MarmotKitException.MediaAttachmentRejected,
            is MarmotKitException.MediaUnfetchable,
            is MarmotKitException.AttachmentModeRequired,
            is MarmotKitException.AttachmentAccountSignedOut,
            is NativeAttachmentTerminalException,
            -> true
            else -> false
        }
