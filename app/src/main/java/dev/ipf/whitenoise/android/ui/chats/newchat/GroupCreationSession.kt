package dev.ipf.whitenoise.android.ui.chats.newchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fences process-owned mutations from a dismissed or replaced group setup screen. */
internal class GroupCreationSession(
    private val nativeOwner: (() -> Boolean)? = null,
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
        if (!isCurrent()) throw CancellationException("Group creation screen was replaced")
    }

    /** An accepted create may finish its captured-account policy commit while that native runtime still exists. */
    fun ensureNativeCurrent() {
        if (!(nativeOwner?.invoke() ?: isCurrent())) {
            throw CancellationException("Captured group runtime was replaced")
        }
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
