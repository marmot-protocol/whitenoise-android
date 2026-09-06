package dev.ipf.whitenoise.android.state

import java.util.concurrent.atomic.AtomicLong

/**
 * Transfers the newest recovery generation from subscription publication to
 * the next chat-list projection without relying on dispatcher confinement.
 */
internal class RecoveryProjectionGenerationHandoff {
    private val pendingGeneration = AtomicLong(NO_RECOVERY_GENERATION)

    /** Retains the newest valid generation published by any subscription callback. */
    fun publish(generation: Long) {
        if (generation <= NO_RECOVERY_GENERATION) return
        pendingGeneration.accumulateAndGet(generation, ::maxOf)
    }

    /** Atomically consumes the pending generation, returning zero when none remains. */
    fun consume(): Long = pendingGeneration.getAndSet(NO_RECOVERY_GENERATION)

    /** Drops any pending generation when the owning chat-list controller is cleared. */
    fun clear() {
        pendingGeneration.set(NO_RECOVERY_GENERATION)
    }
}

private const val NO_RECOVERY_GENERATION = 0L
