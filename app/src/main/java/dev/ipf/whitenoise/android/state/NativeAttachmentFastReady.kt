package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
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
 * engine consumed a change notification could drop that wakeup and stall observation. It is released only once its
 * result has been handed to the caller, so a result delivered while an authoritative read was in flight is not lost.
 *
 * An authoritative terminal state is not reported from here. The feed carries the engine's own typed failure, which the
 * caller classifies, so a terminal state only stops the cross-checking and the feed decides how the transfer ended.
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
        if (peek != null) authoritativeChange(pending, current, peek)?.let { return it }
        return pending.await().also { wait = null }
    }

    /** Stops waiting. A read still blocked in the engine ends when the caller closes the feed it owns. */
    fun cancel() = scope.cancel()

    /**
     * Cross-checks the authoritative state at a growing interval and returns the first non-terminal change, or null
     * once the feed has delivered or the authoritative state is terminal. A blocked authoritative read never holds
     * back a feed result, because the two are raced.
     */
    @Suppress("ReturnCount") // The feed delivering, a terminal state and a change each end the cross-check.
    private suspend fun authoritativeChange(
        pending: Deferred<AttachmentTransferStateFfi>,
        current: AttachmentTransferStateFfi?,
        peek: NativeStatePeek,
    ): AttachmentTransferStateFfi? {
        var window = NATIVE_FAST_PEEK_INITIAL_MILLIS
        while (true) {
            if (withTimeoutOrNull(window) { pending.await() } != null) return null
            val read = scope.async { runCatchingCancellable { peek() }.getOrNull() }
            try {
                val fedFirst =
                    select<Boolean> {
                        pending.onAwait { true }
                        read.onAwait { false }
                    }
                if (fedFirst) return null
                val authoritative = read.await()
                if (authoritative in NATIVE_TRANSFER_TERMINAL_FAILURES) return null
                if (authoritative != null && authoritative != current) return authoritative
            } finally {
                read.cancel()
            }
            window = minOf(window * 2, NATIVE_FAST_PEEK_MAX_MILLIS)
        }
    }
}
