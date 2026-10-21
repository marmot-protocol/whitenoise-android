package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Identity of one native candidate read; epochs prevent sharing across account/runtime replacement. */
internal data class NotificationCandidateReadKey(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val postEpoch: Long,
    val accountCacheEpoch: Long,
)

/**
 * Bounds waiting without pretending a synchronous binding is cancellable. One read per conversation and at most
 * four overall remain admitted until their actual completion. A retry joins its own pending read; another chat
 * can use a different slot. Saturation fails closed rather than spawning unbounded native work.
 */
internal class NotificationCandidateReadCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingRead>()

    /** Waits only for [timeoutMillis]; caller cancellation/timeout never frees an unfinished binding's slot. */
    suspend fun read(
        key: NotificationCandidateReadKey,
        timeoutMillis: Long,
        block: suspend () -> NotificationCandidateState,
    ): NotificationCandidateState? {
        val result = synchronized(lock) { admit(key, block) } ?: return null
        result.start()
        return withTimeoutOrNull(timeoutMillis) { result.await() }
    }

    /** Shares only an identical candidate; completion owns removal even when all awaiters have left. */
    private fun admit(
        key: NotificationCandidateReadKey,
        block: suspend () -> NotificationCandidateState,
    ): Deferred<NotificationCandidateState>? {
        val conversation = key.accountRef to key.groupIdHex.lowercase()
        pending[conversation]?.let { existing -> return existing.result.takeIf { existing.key == key } }
        if (pending.size >= MAX_PENDING_READS) return null
        val result = scope.async(dispatcher, start = CoroutineStart.LAZY) { block() }
        val admitted = PendingRead(key, result)
        pending[conversation] = admitted
        result.invokeOnCompletion {
            synchronized(lock) {
                if (pending[conversation] === admitted) pending.remove(conversation)
            }
        }
        return result
    }

    private data class PendingRead(
        val key: NotificationCandidateReadKey,
        val result: Deferred<NotificationCandidateState>,
    )

    private companion object {
        const val MAX_PENDING_READS = 4
    }
}
