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
 * silently suppress the next candidate. Once a bounded await expires, a different candidate in that conversation
 * is rejected immediately; global admission also rejects when all four holders have expired awaits. Identical
 * retries can still join an abandoned read. Its slot remains occupied until actual completion, bounding native work.
 */
internal class NotificationCandidateReadCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingRead>()
    private val admissionVersion = MutableStateFlow(0L)

    /** Bounds admission and waiting; an expired await marks its unfinished holder without releasing the slot. */
    suspend fun read(
        key: NotificationCandidateReadKey,
        timeoutMillis: Long,
        block: suspend () -> NotificationCandidateState,
    ): NotificationCandidateState? {
        var awaited: PendingRead? = null
        val state = withTimeoutOrNull(timeoutMillis) { awaitRead(key, block) { awaited = it } }
        if (state == null) awaited?.let(::abandon)
        return state
    }

    /** Retries busy admission on completion or abandonment without renewing the caller's outer deadline. */
    private suspend fun awaitRead(
        key: NotificationCandidateReadKey,
        block: suspend () -> NotificationCandidateState,
        onAwait: (PendingRead) -> Unit,
    ): NotificationCandidateState? {
        while (true) {
            when (val admission = synchronized(lock) { admit(key, block) }) {
                is ReadAdmission.Granted -> {
                    onAwait(admission.read)
                    admission.read.result.start()
                    return admission.read.result.await()
                }
                is ReadAdmission.Busy -> admissionVersion.first { it != admission.version }
                ReadAdmission.Rejected -> return null
            }
        }
    }

    /** Wakes admission waiters when a holder's first expired await makes further waiting unnecessary. */
    private fun abandon(read: PendingRead) {
        synchronized(lock) {
            if (!read.result.isCompleted && !read.abandoned) {
                read.abandoned = true
                admissionVersion.value += 1L
            }
        }
    }

    /** Shares identical reads, waits for healthy holders and rejects known-abandoned saturation. Runs under lock. */
    private fun admit(
        key: NotificationCandidateReadKey,
        block: suspend () -> NotificationCandidateState,
    ): ReadAdmission {
        if (pending.entries.removeAll { it.value.result.isCompleted }) admissionVersion.value += 1L
        val conversation = key.accountRef to key.groupIdHex.lowercase()
        val existing = pending[conversation]
        return when {
            existing?.key == key -> ReadAdmission.Granted(existing)
            existing?.abandoned == true -> ReadAdmission.Rejected
            pending.size >= MAX_PENDING_READS && pending.values.all { it.abandoned } -> ReadAdmission.Rejected
            existing != null || pending.size >= MAX_PENDING_READS -> ReadAdmission.Busy(admissionVersion.value)
            else -> {
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
                ReadAdmission.Granted(admitted)
            }
        }
    }

    private data class PendingRead(
        val key: NotificationCandidateReadKey,
        val result: Deferred<NotificationCandidateState>,
        // Accessed only under lock; an expired await does not imply binding cancellation or completion.
        var abandoned: Boolean = false,
    )

    private sealed interface ReadAdmission {
        data class Granted(
            val read: PendingRead,
        ) : ReadAdmission

        data class Busy(
            val version: Long,
        ) : ReadAdmission

        data object Rejected : ReadAdmission
    }

    private companion object {
        const val MAX_PENDING_READS = 4
    }
}
