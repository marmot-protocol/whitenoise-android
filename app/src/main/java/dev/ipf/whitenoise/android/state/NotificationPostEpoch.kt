package dev.ipf.whitenoise.android.state

import java.util.concurrent.atomic.AtomicLong

/** Invalidates notification work that spans a visible-conversation transition. */
internal class NotificationPostEpoch {
    private val value = AtomicLong(0)

    fun capture(): Long = value.get()

    fun advance() {
        value.incrementAndGet()
    }

    fun isCurrent(captured: Long): Boolean = value.get() == captured
}
