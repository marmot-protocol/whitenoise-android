package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal const val MAX_DEFERRED_HOST_PERFORMANCE_EMITTERS = 128

/** Every shared MDK operation Android can truthfully own; Linux-only stages stay unreported. */
internal val ANDROID_HOST_PERFORMANCE_OPERATIONS =
    setOf(
        HostPerformanceOperationFfi.SPLASH_READY,
        HostPerformanceOperationFfi.FOREGROUND_LOCAL_READY,
        HostPerformanceOperationFfi.OUTBOUND_MESSAGE_VISIBLE,
        HostPerformanceOperationFfi.INBOUND_MESSAGE_VISIBLE,
        HostPerformanceOperationFfi.CONVERSATION_LOCAL_VISIBLE,
        HostPerformanceOperationFfi.CONVERSATION_COMPOSER_READY,
        HostPerformanceOperationFfi.WINDOW_INIT,
        HostPerformanceOperationFfi.FONTS_INIT,
        HostPerformanceOperationFfi.RUNTIME_INIT,
        HostPerformanceOperationFfi.ACCOUNT_LOAD,
        HostPerformanceOperationFfi.ACCOUNT_SWITCH,
        HostPerformanceOperationFfi.FRAME_UPDATE,
        HostPerformanceOperationFfi.FRAME_LAYOUT,
        HostPerformanceOperationFfi.FRAME_DRAW,
        HostPerformanceOperationFfi.FRAME_PRESENT,
        HostPerformanceOperationFfi.CHAT_LIST_LOAD,
        HostPerformanceOperationFfi.CONTACTS_LOAD,
        HostPerformanceOperationFfi.ARCHIVED_CHAT_LIST_LOAD,
        HostPerformanceOperationFfi.PROFILE_LOAD,
        HostPerformanceOperationFfi.PROFILE_READ,
        HostPerformanceOperationFfi.TIMELINE_OPEN,
        HostPerformanceOperationFfi.TIMELINE_PAGE,
        HostPerformanceOperationFfi.TIMELINE_HANDOFF,
        HostPerformanceOperationFfi.TIMELINE_APPLY,
        HostPerformanceOperationFfi.MESSAGE_SEND,
        HostPerformanceOperationFfi.MESSAGE_SEARCH,
        HostPerformanceOperationFfi.CONVERSATION_SEARCH,
        HostPerformanceOperationFfi.MEDIA_QUEUE_WAIT,
        HostPerformanceOperationFfi.MEDIA_PREPARE,
        HostPerformanceOperationFfi.MEDIA_LOAD,
        HostPerformanceOperationFfi.MEDIA_CACHE_READ,
        HostPerformanceOperationFfi.MEDIA_DECODE,
        HostPerformanceOperationFfi.MEDIA_APPLY,
        HostPerformanceOperationFfi.SETTINGS_SAVE,
    )

/** Receives one closed-schema host duration without adding caller-controlled labels. */
internal fun interface HostPerformanceEmitter {
    fun emit(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    )
}

/**
 * Creates exactly-once host timing attempts bound to the runtime generation that started them.
 *
 * The emitter is captured at [begin] so a late callback can never report into a replacement MDK
 * runtime. Generation replacement converts an otherwise successful late completion to cancellation.
 */
internal class HostPerformanceRecorder(
    private val generation: () -> Int,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val emitterLock = Any()
    private var currentEmitter: BoundHostPerformanceEmitter? = null
    private val deferredEmitters = mutableListOf<DeferredHostPerformanceEmitter>()

    /** Starts an attempt with a monotonic timestamp and the current runtime owner. */
    fun begin(
        operation: HostPerformanceOperationFfi,
        startedAtMs: Long = nowMs(),
    ): HostPerformanceAttempt {
        val ownerGeneration = generation()
        return HostPerformanceAttempt(
            operation = operation,
            startedAtMs = startedAtMs,
            generation = ownerGeneration,
            currentGeneration = generation,
            emitter = emitterFor(ownerGeneration),
            nowMs = nowMs,
        )
    }

    /** Records an already-completed stage against the runtime that is current at this call. */
    fun record(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    ) {
        val ownerGeneration = generation()
        emitterFor(ownerGeneration).emit(operation, durationMs.coerceAtLeast(0L), outcome)
    }

    /**
     * Publishes one runtime owner and replays only samples captured for its generation.
     *
     * Deferred attempts are bound before leaving this call, so a later replacement cannot
     * inherit them even when the app-level generation number has not changed.
     */
    fun publishEmitter(
        owner: Any,
        ownerGeneration: Int,
        emitter: HostPerformanceEmitter,
    ) {
        val matching: List<DeferredHostPerformanceEmitter>
        val stale: List<DeferredHostPerformanceEmitter>
        synchronized(emitterLock) {
            currentEmitter = BoundHostPerformanceEmitter(owner, ownerGeneration, emitter)
            matching = deferredEmitters.filter { it.generation == ownerGeneration }
            stale = deferredEmitters.filterNot { it.generation == ownerGeneration }
            deferredEmitters.clear()
        }
        stale.forEach(DeferredHostPerformanceEmitter::discard)
        matching.forEach { it.bind(emitter) }
    }

    /** Stops assigning new work to [owner] without detaching attempts it already owns. */
    fun clearEmitter(owner: Any) {
        synchronized(emitterLock) {
            if (currentEmitter?.owner === owner) currentEmitter = null
        }
    }

    /** Measures one suspending block and maps every exit to a terminal MDK outcome. */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // The outer boundary must classify every terminal exit.
    suspend fun <T> measure(
        operation: HostPerformanceOperationFfi,
        block: suspend () -> T,
    ): T {
        val attempt = begin(operation)
        return try {
            block().also { attempt.success() }
        } catch (timeout: TimeoutCancellationException) {
            attempt.timeout()
            throw timeout
        } catch (cancel: CancellationException) {
            attempt.cancel()
            throw cancel
        } catch (throwable: Throwable) {
            attempt.failure()
            throw throwable
        }
    }

    /** Returns the published owner or a replayable sink created atomically with publication. */
    private fun emitterFor(ownerGeneration: Int): HostPerformanceEmitter =
        synchronized(emitterLock) {
            currentEmitter
                ?.takeIf { it.generation == ownerGeneration }
                ?.emitter
                ?: DeferredHostPerformanceEmitter(ownerGeneration).also { deferred ->
                    if (deferredEmitters.size >= MAX_DEFERRED_HOST_PERFORMANCE_EMITTERS) {
                        deferredEmitters.removeAt(0).discard()
                    }
                    deferredEmitters.add(deferred)
                }
        }
}

/**
 * Measures work whose success boundary is an explicit commit callback rather than block return.
 *
 * A normally returned stale-generation apply is cancelled, while thrown exits preserve their
 * timeout, cancellation, or failure classification.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend fun <T> measureHostPerformanceCommit(
    attempt: HostPerformanceAttempt,
    block: suspend (onCommitted: () -> Unit) -> T,
): T {
    var committed = false
    return try {
        block {
            committed = true
            attempt.success()
        }
    } catch (timeout: TimeoutCancellationException) {
        attempt.timeout()
        throw timeout
    } catch (cancel: CancellationException) {
        attempt.cancel()
        throw cancel
    } catch (throwable: Throwable) {
        attempt.failure()
        throw throwable
    } finally {
        if (!committed) attempt.cancel()
    }
}

/** One currently published runtime emitter and its identity fence. */
private data class BoundHostPerformanceEmitter(
    val owner: Any,
    val generation: Int,
    val emitter: HostPerformanceEmitter,
)

/**
 * Holds one pre-runtime sample or attempt until its first matching runtime is published.
 *
 * Binding and completion may race across the bootstrap IO and UI threads. The first matching
 * bind delivers at most one stored sample; discarding a stale generation is terminal.
 */
private class DeferredHostPerformanceEmitter(
    val generation: Int,
) : HostPerformanceEmitter {
    private val lock = Any()
    private var target: HostPerformanceEmitter? = null
    private var pending: DeferredHostPerformanceSample? = null
    private var discarded = false

    /** Stores one sample until binding or forwards it to the already-bound owner. */
    override fun emit(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    ) {
        val sample = DeferredHostPerformanceSample(operation, durationMs, outcome)
        val bound =
            synchronized(lock) {
                if (discarded || pending != null) return
                target ?: run {
                    pending = sample
                    null
                }
            }
        bound?.emit(sample.operation, sample.durationMs, sample.outcome)
    }

    /** Binds exactly once and drains a sample that completed before runtime publication. */
    fun bind(emitter: HostPerformanceEmitter) {
        val stored =
            synchronized(lock) {
                if (discarded || target != null) return
                target = emitter
                pending.also { pending = null }
            }
        stored?.let { emitter.emit(it.operation, it.durationMs, it.outcome) }
    }

    /** Permanently drops a sample owned by a generation that was never published. */
    fun discard() {
        synchronized(lock) {
            discarded = true
            pending = null
            target = null
        }
    }
}

/** One closed-schema sample retained only until its owning runtime is available. */
private data class DeferredHostPerformanceSample(
    val operation: HostPerformanceOperationFfi,
    val durationMs: Long,
    val outcome: HostPerformanceOutcomeFfi,
)

/** One generation-fenced timing attempt whose first terminal outcome wins. */
internal class HostPerformanceAttempt(
    private val operation: HostPerformanceOperationFfi,
    private val startedAtMs: Long,
    private val generation: Int,
    private val currentGeneration: () -> Int,
    private val emitter: HostPerformanceEmitter?,
    private val nowMs: () -> Long,
) {
    private val settled = AtomicBoolean(false)

    /** Completes the attempt successfully if its runtime owner is still current. */
    fun success(): Boolean = complete(HostPerformanceOutcomeFfi.SUCCESS)

    /** Completes the attempt as a failure without exposing exception details. */
    fun failure(): Boolean = complete(HostPerformanceOutcomeFfi.FAILURE)

    /** Completes the attempt as cancelled. */
    fun cancel(): Boolean = complete(HostPerformanceOutcomeFfi.CANCELLED)

    /** Completes the attempt as timed out. */
    fun timeout(): Boolean = complete(HostPerformanceOutcomeFfi.TIMEOUT)

    /** Completes the attempt when the requested host capability was not ready. */
    fun unavailable(): Boolean = complete(HostPerformanceOutcomeFfi.UNAVAILABLE)

    /** Emits at most once and fences a stale generation as cancellation. */
    fun complete(requestedOutcome: HostPerformanceOutcomeFfi): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val outcome =
            if (currentGeneration() == generation) {
                requestedOutcome
            } else {
                HostPerformanceOutcomeFfi.CANCELLED
            }
        emitter?.emit(
            operation,
            (nowMs() - startedAtMs).coerceAtLeast(0L),
            outcome,
        )
        return true
    }
}

/** Owns one replaceable attempt and guarantees that supersession is terminal. */
internal class HostPerformanceAttemptSlot {
    private var active: HostPerformanceAttempt? = null

    /** Replaces the active attempt, cancelling the superseded owner first. */
    @Synchronized
    fun replace(next: HostPerformanceAttempt) {
        active?.cancel()
        active = next
    }

    /** Completes the active attempt only when its host milestone is ready. */
    @Synchronized
    fun successIf(ready: Boolean): Boolean = if (ready) successLocked() else false

    /** Completes and releases the active attempt successfully. */
    @Synchronized
    fun success(): Boolean = successLocked()

    /** Cancels and releases the active attempt. */
    @Synchronized
    fun cancel(): Boolean {
        val attempt = active ?: return false
        active = null
        return attempt.cancel()
    }

    private fun successLocked(): Boolean {
        val attempt = active ?: return false
        active = null
        return attempt.success()
    }
}

/** A claimed set of visibility attempts that shares one rendered-frame outcome. */
internal class HostPerformanceAttemptBatch internal constructor(
    private val attempts: List<HostPerformanceAttempt>,
) {
    val isEmpty: Boolean
        get() = attempts.isEmpty()

    /** Marks every claimed attempt visible after the owning draw/frame boundary. */
    fun success() {
        attempts.forEach(HostPerformanceAttempt::success)
    }

    /** Cancels every claimed attempt when its render owner disappears. */
    fun cancel() {
        attempts.forEach(HostPerformanceAttempt::cancel)
    }
}

/** Holds keyed attempts until the UI atomically claims the published rows it will reveal. */
internal class HostPerformanceAttemptRegistry {
    private val attempts = linkedMapOf<String, HostPerformanceAttempt>()

    /** Registers one identity and cancels an impossible duplicate owner. */
    @Synchronized
    fun register(
        identity: String,
        attempt: HostPerformanceAttempt,
    ) {
        attempts.put(identity, attempt)?.cancel()
    }

    /** Transfers every published attempt to one UI reveal transaction. */
    @Synchronized
    fun claimAll(): HostPerformanceAttemptBatch {
        val claimed = attempts.values.toList()
        attempts.clear()
        return HostPerformanceAttemptBatch(claimed)
    }

    /** Cancels one admitted identity that failed before publication. */
    @Synchronized
    fun cancel(identity: String): Boolean = attempts.remove(identity)?.cancel() ?: false

    /** Cancels attempts that never obtained a UI reveal owner. */
    @Synchronized
    fun cancelAll() {
        attempts.values.forEach(HostPerformanceAttempt::cancel)
        attempts.clear()
    }
}

/** Converts the actual durable preference commit result into one terminal timing outcome. */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // The commit boundary must classify every provider failure.
internal fun completeHostPreferenceCommit(
    attempt: HostPerformanceAttempt,
    commit: () -> Boolean,
) {
    try {
        if (commit()) attempt.success() else attempt.failure()
    } catch (timeout: TimeoutCancellationException) {
        attempt.timeout()
        throw timeout
    } catch (cancel: CancellationException) {
        attempt.cancel()
        throw cancel
    } catch (throwable: Throwable) {
        attempt.failure()
        throw throwable
    }
}
