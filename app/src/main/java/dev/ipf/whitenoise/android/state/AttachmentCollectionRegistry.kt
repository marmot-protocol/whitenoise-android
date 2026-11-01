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

    /** Every live operation by id. */
    val operations: StateFlow<Map<String, AttachmentCollectionOperation>> = state.asStateFlow()

    /** The current time, shared with the runner so journal timestamps agree. */
    fun now(): Long = clock()

    /** Adds [operation] and journals it; false when the journal could not hold it, and nothing is added. */
    fun start(operation: AttachmentCollectionOperation): Boolean {
        if (!journal.write(operation)) return false
        state.update { it + (operation.id to operation) }
        return true
    }

    /**
     * Applies [transform] to one operation and journals the result before returning it, so a token recorded
     * here is on disk before its upload begins. A finished operation is removed and its record deleted.
     */
    fun update(
        operationId: String,
        transform: (AttachmentCollectionOperation) -> AttachmentCollectionOperation,
    ): AttachmentCollectionOperation? {
        var result: AttachmentCollectionOperation? = null
        state.update { current ->
            val operation = current[operationId] ?: return@update current
            val next = transform(operation)
            result = next
            if (next.phase == CollectionOperationPhase.FINISHED) {
                current - operationId
            } else {
                current + (operationId to next)
            }
        }
        result?.let { next ->
            if (next.phase == CollectionOperationPhase.FINISHED) {
                journal.delete(next.accountRef, next.groupIdHex, next.id)
            } else {
                journal.write(next)
            }
        }
        return result
    }

    /** Forgets one operation and its record, after an explicit discard or a lost destination. */
    fun discard(operationId: String) {
        val removed = state.value[operationId] ?: return
        state.update { it - operationId }
        journal.delete(removed.accountRef, removed.groupIdHex, removed.id)
    }

    /** Forgets every operation of a conversation whose destination is no longer valid. */
    fun invalidateConversation(
        accountRef: String,
        groupIdHex: String,
    ) {
        state.update { current ->
            current.filterValues { it.accountRef != accountRef || it.groupIdHex != groupIdHex }
        }
        journal.deleteConversation(accountRef, groupIdHex)
    }

    /** Forgets every operation of a signed-out account. */
    fun forgetAccount(accountRef: String) {
        state.update { current -> current.filterValues { it.accountRef != accountRef } }
        journal.deleteAccount(accountRef)
    }

    /**
     * Journaled operations of one conversation that this process does not already run, with expired
     * records swept first. The caller decides, with MDK, what each one already sent.
     */
    fun journaledFor(
        accountRef: String,
        groupIdHex: String,
    ): List<AttachmentCollectionOperation> {
        journal.sweepExpired()
        val live = state.value.keys
        return journal.read(accountRef, groupIdHex).filter { it.id !in live }
    }

    /** Adopts a restored operation without rewriting its record until it next changes. */
    fun adopt(operation: AttachmentCollectionOperation) {
        state.update { current -> if (operation.id in current) current else current + (operation.id to operation) }
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
