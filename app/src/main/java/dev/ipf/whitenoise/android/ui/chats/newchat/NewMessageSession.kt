package dev.ipf.whitenoise.android.ui.chats.newchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fences process-owned mutations from a dismissed or replaced recipient screen. */
internal class NewMessageSession(
    private val currentOwner: () -> Boolean,
) {
    private var active = true

    /** Reads the live account, including changes before Compose disposes the old screen. */
    fun isCurrent(): Boolean = active && currentOwner()

    /** Invalidates callbacks without cancelling an already accepted native mutation. */
    fun dispose() {
        active = false
    }

    /** Stops the next stage before it can resolve a different account's native owner. */
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("Recipient screen was replaced")
    }

    /** Rejects non-cooperative late values as well as operations that have not started yet. */
    suspend fun <T> currentValue(load: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val value = load()
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        return value
    }
}
