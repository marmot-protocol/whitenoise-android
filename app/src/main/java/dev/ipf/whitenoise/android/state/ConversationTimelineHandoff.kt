package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred

/**
 * Receipt/application acknowledgements for one bounded subscription attempt.
 * No message data is retained here. Foreground work captures only the already
 * received tail; later arrivals cannot extend its presentation wait.
 * The existing bounded receive channel and single sequential apply pump bound
 * outstanding receipts; settlement removes each batch before consuming another.
 */
internal class ConversationTimelineHandoff {
    private val pending = linkedSetOf<CompletableDeferred<Boolean>>()
    private var closed = false

    /** Registers a local receipt before its page crosses back to the main dispatcher. */
    @Synchronized
    fun received(): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { ticket ->
            if (closed) ticket.complete(false) else pending.add(ticket)
        }

    /** Captures the current local handoff without starting a native read or waiting for a future receipt. */
    @Synchronized
    fun pendingAtForeground(): CompletableDeferred<Boolean>? = pending.lastOrNull()

    /** Settles a batch only after the actual commit callback or explicit supersession. */
    @Synchronized
    fun settled(
        tickets: List<CompletableDeferred<Boolean>>,
        applied: Boolean,
    ) {
        tickets.forEach { ticket ->
            if (pending.remove(ticket)) ticket.complete(applied)
        }
    }

    /** Retirement wakes every waiter without admitting an uncommitted or replacement-owner page. */
    @Synchronized
    fun close() {
        closed = true
        pending.forEach { it.complete(false) }
        pending.clear()
    }
}
