package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/** Reads the authoritative state of the observed transfer. It never demands, retries or cancels work. */
internal typealias NativeStatePeek = suspend () -> AttachmentTransferStateFfi?

/** First wait before the engine's feed is cross-checked against the authoritative state. */
internal const val NATIVE_FAST_PEEK_INITIAL_MILLIS = 10L

/** The cross-check backs off to the engine's own coalescing cadence, so it never reads faster than the feed would. */
internal const val NATIVE_FAST_PEEK_MAX_MILLIS = 250L

/**
 * One in-flight wait on the engine's subscription.
 *
 * The engine coalesces replacement snapshots to at most one per 250 ms, including a terminal one, so a transfer that
 * finishes inside that window is announced late. This wait keeps the feed read pending and races it against short,
 * backed-off authoritative reads. The pending read is never cancelled to start another, because cancelling it after the
 * engine consumed a change notification could drop that wakeup and stall observation.
 */
internal class PendingFeedWait(
    private val updates: NativeTransferFeed,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wait: Deferred<AttachmentTransferStateFfi>? = null

    /** Returns the next state that either the feed delivers or the authoritative read shows to have changed. */
    suspend fun next(
        current: AttachmentTransferStateFfi?,
        peek: NativeStatePeek?,
    ): AttachmentTransferStateFfi {
        val pending = wait ?: scope.async { updates.nextState() }.also { wait = it }
        val state = if (peek == null) pending.await() else raceAuthoritative(pending, current, peek)
        if (pending.isCompleted) wait = null
        return state
    }

    /** Stops waiting. A read still blocked in the engine ends when the caller closes the feed it owns. */
    fun cancel() = scope.cancel()

    /** Waits for the feed, cross-checking the authoritative state at a growing interval until a change appears. */
    private suspend fun raceAuthoritative(
        pending: Deferred<AttachmentTransferStateFfi>,
        current: AttachmentTransferStateFfi?,
        peek: NativeStatePeek,
    ): AttachmentTransferStateFfi {
        var window = NATIVE_FAST_PEEK_INITIAL_MILLIS
        while (true) {
            withTimeoutOrNull(window) { pending.await() }?.let { return it }
            val authoritative = runCatchingCancellable { peek() }.getOrNull()
            if (authoritative != null && authoritative != current) return authoritative
            window = minOf(window * 2, NATIVE_FAST_PEEK_MAX_MILLIS)
        }
    }
}
