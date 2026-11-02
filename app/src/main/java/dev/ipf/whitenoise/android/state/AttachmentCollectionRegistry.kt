package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.WeakHashMap

/**
 * Process-wide owner of collection sends. Operations live here rather than in a screen, so rotation,
 * activity recreation and backgrounding keep them; every change is mirrored to the metadata journal so a
 * restarted process can recover them. A finished, discarded or invalidated operation leaves both at once.
 */
internal class AttachmentCollectionRegistry(
    private val journal: AttachmentCollectionJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<Map<String, AttachmentCollectionOperation>>(emptyMap())
    private val resuming = mutableSetOf<String>()

    // One owner for each transition and its journal effect, so a write from an earlier transition can never
    // land after a later discard and bring the record back.
    private val lock = Any()

    /** Every live operation by id. */
    val operations: StateFlow<Map<String, AttachmentCollectionOperation>> = state.asStateFlow()

    /** The current time, shared with the runner so journal timestamps agree. */
    fun now(): Long = clock()

    /** Adds [operation] and journals it; false when the journal could not hold it, and nothing is added. */
    fun start(operation: AttachmentCollectionOperation): Boolean =
        synchronized(lock) {
            journal.write(operation).also { written ->
                if (written) state.update { it + (operation.id to operation) }
            }
        }

    /**
     * Applies [transform] to one operation and journals the result before returning it. A finished operation
     * is removed and its record deleted. When [durable], a result the journal could not record is not applied
     * and null is returned, as for a missing operation: a token must be on disk before its upload begins.
     */
    fun update(
        operationId: String,
        durable: Boolean = false,
        transform: (AttachmentCollectionOperation) -> AttachmentCollectionOperation,
    ): AttachmentCollectionOperation? =
        synchronized(lock) {
            state.value[operationId]?.let(transform)?.takeIf { next ->
                if (next.phase == CollectionOperationPhase.FINISHED) {
                    journal.delete(next.accountRef, next.groupIdHex, next.id)
                    state.update { it - operationId }
                    true
                } else {
                    val applied = journal.write(next) || !durable
                    if (applied) state.update { it + (operationId to next) }
                    applied
                }
            }
        }

    /** Forgets one operation and its record, after an explicit discard or a lost destination. */
    fun discard(operationId: String) {
        synchronized(lock) {
            val removed = state.value[operationId] ?: return
            state.update { it - operationId }
            journal.delete(removed.accountRef, removed.groupIdHex, removed.id)
        }
    }

    /** Forgets every operation of a conversation whose destination is no longer valid. */
    fun invalidateConversation(
        accountRef: String,
        groupIdHex: String,
    ) {
        synchronized(lock) {
            state.update { current ->
                current.filterValues { it.accountRef != accountRef || it.groupIdHex != groupIdHex }
            }
            journal.deleteConversation(accountRef, groupIdHex)
        }
    }

    /** Forgets every operation of a signed-out account. */
    fun forgetAccount(accountRef: String) {
        synchronized(lock) {
            state.update { current -> current.filterValues { it.accountRef != accountRef } }
            journal.deleteAccount(accountRef)
        }
    }

    /**
     * Journaled operations of one conversation that this process does not already run, with expired
     * records swept first. The caller decides, with MDK, what each one already sent.
     */
    fun journaledFor(
        accountRef: String,
        groupIdHex: String,
    ): List<AttachmentCollectionOperation> =
        synchronized(lock) {
            journal.sweepExpired()
            val live = state.value.keys
            journal.read(accountRef, groupIdHex).filter { it.id !in live }
        }

    /** Adopts a restored operation without rewriting its record until it next changes. */
    fun adopt(operation: AttachmentCollectionOperation) {
        synchronized(lock) {
            state.update { current -> if (operation.id in current) current else current + (operation.id to operation) }
        }
    }

    /** Claims the resume of [operationId]; false while another resume of it is still in progress. */
    fun claimResume(operationId: String): Boolean = synchronized(resuming) { resuming.add(operationId) }

    /** Ends a resume claim once the operation is no longer paused, or the resume gave up. */
    fun releaseResume(operationId: String) {
        synchronized(resuming) { resuming.remove(operationId) }
    }
}

private val registries = WeakHashMap<WhiteNoiseAppState, AttachmentCollectionRegistry>()

/** This app state's collection registry, journaling under the app's no-backup directory. */
internal val WhiteNoiseAppState.attachmentCollections: AttachmentCollectionRegistry
    get() =
        synchronized(registries) {
            registries.getOrPut(this) {
                AttachmentCollectionRegistry(AttachmentCollectionJournal(appContext.noBackupFilesDir))
            }
        }
