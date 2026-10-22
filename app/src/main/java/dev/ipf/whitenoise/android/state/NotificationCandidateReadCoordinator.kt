package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * can use a different slot. Busy admission waits within the caller's deadline, so healthy concurrent reads do not
 * silently suppress the next candidate. A stalled binding still retains its slot and cannot multiply native work.
 */
internal class NotificationCandidateReadCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingRead>()
    private val admissionVersion = MutableStateFlow(0L)

    /** Bounds admission and result waiting together; timeout never frees an unfinished binding's slot. */
    suspend fun read(
        key: NotificationCandidateReadKey,
        timeoutMillis: Long,
        block: suspend () -> NotificationCandidateState,
    ): NotificationCandidateState? = withTimeoutOrNull(timeoutMillis) { awaitRead(key, block) }

    /** Retries busy admission on actual retirement without renewing the caller's outer deadline. */
    private suspend fun awaitRead(
        key: NotificationCandidateReadKey,
        block: suspend () -> NotificationCandidateState,
    ): NotificationCandidateState {
        while (true) {
            val admission = synchronized(lock) { ReadAdmission(admit(key, block), admissionVersion.value) }
            val result = admission.result
            if (result != null) {
                result.start()
                return result.await()
            }
            admissionVersion.first { it != admission.version }
        }
    }

    /** Shares only unfinished identical reads; completion publication can precede its cleanup callback. */
    private fun admit(
        key: NotificationCandidateReadKey,
        block: suspend () -> NotificationCandidateState,
    ): Deferred<NotificationCandidateState>? {
        if (pending.entries.removeAll { it.value.result.isCompleted }) admissionVersion.value += 1L
        val conversation = key.accountRef to key.groupIdHex.lowercase()
        val existing = pending[conversation]
        if (existing != null || pending.size >= MAX_PENDING_READS) {
            return existing?.result?.takeIf { existing.key == key }
        }
        val result = scope.async(dispatcher, start = CoroutineStart.LAZY) { block() }
        val admitted = PendingRead(key, result)
        pending[conversation] = admitted
        result.invokeOnCompletion {
            synchronized(lock) {
                if (pending[conversation] === admitted) {
                    pending.remove(conversation)
                    admissionVersion.value += 1L
                }
            }
        }
        return result
    }

    private data class PendingRead(
        val key: NotificationCandidateReadKey,
        val result: Deferred<NotificationCandidateState>,
    )

    private data class ReadAdmission(
        val result: Deferred<NotificationCandidateState>?,
        val version: Long,
    )

    private companion object {
        const val MAX_PENDING_READS = 4
    }
}
