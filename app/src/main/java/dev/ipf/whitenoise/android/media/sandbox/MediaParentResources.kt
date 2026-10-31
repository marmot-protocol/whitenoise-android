package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** AV parent descriptors retain admission until their one close returns successfully. */
internal class MediaParentResources {
    private val held = mutableSetOf<MediaParentResource<*>>()
    private var failed = false
    private var tasks = 0
    private val closer: (AutoCloseable) -> Unit = { it.close() }

    @Synchronized fun <T : AutoCloseable> own(value: T): MediaParentResource<T> =
        MediaParentResource(
            value,
            this,
        ).also { held += it }

    internal fun closeValue(value: AutoCloseable) = closer(value)

    @Synchronized fun available(): Boolean = held.isEmpty() && tasks == 0 && !failed

    @Synchronized fun healthy(): Boolean = !failed

    @Synchronized fun task(): MediaParentTask {
        tasks++
        return MediaParentTask(this)
    }

    @Synchronized internal fun taskFinished() {
        check(tasks > 0)
        tasks--
    }

    @Synchronized fun uncertain() {
        failed = true
    }

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

    fun start(): Boolean = state.compareAndSet(0, 1)

    fun cancelBeforeStart() {
        if (state.compareAndSet(0, 2)) owner.taskFinished()
    }

    fun finish() {
        if (state.getAndSet(2) != 2) owner.taskFinished()
    }
}
