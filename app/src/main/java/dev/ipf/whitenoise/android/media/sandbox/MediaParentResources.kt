package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** AV parent descriptors retain admission until their one close returns successfully. */
internal class MediaParentResources {
    private val held = mutableSetOf<MediaParentResource<*>>()
    private var failed = false
    private var tasks = 0
    private val closer: (AutoCloseable) -> Unit = { it.close() }

    /**
     * Registers a parent capability until its tracked close succeeds; registration does not close it.
     */
    @Synchronized fun <T : AutoCloseable> own(value: T): MediaParentResource<T> =
        MediaParentResource(
            value,
            this,
        ).also { held += it }

    /**
     * Invokes the underlying close once per tracked resource attempt; failures propagate to its owner.
     */
    internal fun closeValue(value: AutoCloseable) = closer(value)

    /**
     * Allows fresh admission only when all resources and cleanup tasks are discharged and no close is uncertain.
     */
    @Synchronized fun available(): Boolean = held.isEmpty() && tasks == 0 && !failed

    /**
     * Returns true while no resource close or cleanup submission has recorded uncertain ownership.
     */
    @Synchronized fun healthy(): Boolean = !failed

    /**
     * Reserves cleanup credit before scheduling, so admission stays occupied while cleanup may still run.
     */
    @Synchronized fun task(): MediaParentTask {
        tasks++
        return MediaParentTask(this)
    }

    /**
     * Discharges one reserved cleanup credit; completing an unreserved task is a programming error.
     */
    @Synchronized internal fun taskFinished() {
        check(tasks > 0)
        tasks--
    }

    /**
     * Permanently refuses fresh admission for this resource owner after uncertain cleanup.
     */
    @Synchronized fun uncertain() {
        failed = true
    }

    /**
     * Releases a capability only after successful close; failure retains it and poisons admission.
     */
    @Synchronized internal fun finished(
        resource: MediaParentResource<*>,
        success: Boolean,
    ) {
        if (success) held.remove(resource) else failed = true
    }
}

internal class MediaParentResource<T : AutoCloseable>(
    val value: T,
    private val owner: MediaParentResources? = null,
) : AutoCloseable {
    private val closing = AtomicBoolean()

    /**
     * Closes an owned capability at most once, retaining failed-close admission without retry.
     * An untracked resource delegates directly to its original close semantics.
     */
    @Suppress("TooGenericExceptionCaught") // Any uncertain close retains its exact AV resource; never replay it.
    override fun close() {
        if (owner == null) {
            value.close() // Existing image-source semantics are unchanged.
            return
        }
        if (!closing.compareAndSet(false, true)) return
        try {
            owner.closeValue(value)
            owner.finished(this, true)
        } catch (failure: Throwable) {
            owner.finished(this, false)
            throw failure
        }
    }
}

/** A queued cleanup cannot lose credit through Future.cancel(false) while it is already running. */
internal class MediaParentTask(
    private val owner: MediaParentResources,
) {
    private val state = AtomicInteger()

    /**
     * Atomically claims a queued cleanup; returns false if cancellation or another start already won.
     */
    fun start(): Boolean = state.compareAndSet(0, 1)

    /**
     * Discharges credit only if cleanup never started; running cleanup retains its credit.
     */
    fun cancelBeforeStart() {
        if (state.compareAndSet(0, 2)) owner.taskFinished()
    }

    /**
     * Marks cleanup terminal and discharges its credit once, including after cancellation races.
     */
    fun finish() {
        if (state.getAndSet(2) != 2) owner.taskFinished()
    }
}
