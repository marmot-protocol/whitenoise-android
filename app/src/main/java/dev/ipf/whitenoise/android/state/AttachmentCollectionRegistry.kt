package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Process-wide owner of collection sends. Operations live here rather than in a screen, so rotation,
 * activity recreation and backgrounding keep them; every change is mirrored to the metadata journal so a
 * restarted process can recover them. A finished, discarded or invalidated operation leaves both at once.
 *
 * Transitions change the in-memory state at once, on the caller's thread, and queue their journal effect on
 * one background thread in the same order, so no caller encodes or writes the journal on the main thread and
 * a write from an earlier transition can never land after a later deletion.
 */
internal class AttachmentCollectionRegistry(
    private val journal: AttachmentCollectionJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<Map<String, AttachmentCollectionOperation>>(emptyMap())

    // Orders each transition's state change with the queuing of its journal effect.
    private val lock = Any()

    /** Every live operation by id. */
    val operations: StateFlow<Map<String, AttachmentCollectionOperation>> = state.asStateFlow()

    /** The current time, shared with the runner so journal timestamps agree. */
    fun now(): Long = clock()

    /**
     * Adds [operation] and journals it; false when the journal could not hold it, and the operation is removed
     * again. Blocks on the journal, so it is called off the main thread.
     */
    fun start(operation: AttachmentCollectionOperation): Boolean {
        // Live before its record exists, so a concurrent restore never adopts and deletes it as a stray record.
        val effect =
            synchronized(lock) {
                state.update { it + (operation.id to operation) }
                journaled { journal.write(operation) }
            }
        val written = effect.get()
        if (!written) discard(operation.id)
        return written
    }

    /**
     * Applies [transform] to one operation and queues its journal effect. A finished operation is removed and
     * its record deleted. Returns the result, or null when the operation is gone.
     */
    fun update(
        operationId: String,
        transform: (AttachmentCollectionOperation) -> AttachmentCollectionOperation,
    ): AttachmentCollectionOperation? = synchronized(lock) { apply(operationId, transform)?.first }

    /**
     * Like [update], but waits until the journal holds the result: null when the operation is gone or the
     * journal could not record it. A chunk token must be on disk before its upload begins. Memory has already
     * advanced when the write fails, so the caller corrects it.
     */
    suspend fun updateDurably(
        operationId: String,
        transform: (AttachmentCollectionOperation) -> AttachmentCollectionOperation,
    ): AttachmentCollectionOperation? {
        val (next, effect) = synchronized(lock) { apply(operationId, transform) } ?: return null
        return next.takeIf { withContext(Dispatchers.IO) { effect.get() } }
    }

    /** Applies one transition in memory and queues its journal effect; the caller holds [lock]. */
    private fun apply(
        operationId: String,
        transform: (AttachmentCollectionOperation) -> AttachmentCollectionOperation,
    ): Pair<AttachmentCollectionOperation, Future<Boolean>>? {
        val next = state.value[operationId]?.let(transform) ?: return null
        val effect =
            if (next.phase == CollectionOperationPhase.FINISHED) {
                state.update { it - operationId }
                journaled {
                    journal.delete(next.accountRef, next.groupIdHex, next.id)
                    true
                }
            } else {
                state.update { it + (operationId to next) }
                journaled { journal.write(next) }
            }
        return next to effect
    }

    /** Forgets one operation and its record, after an explicit discard or a lost destination. */
    fun discard(operationId: String) {
        synchronized(lock) {
            val removed = state.value[operationId] ?: return
            state.update { it - operationId }
            journaled {
                journal.delete(removed.accountRef, removed.groupIdHex, removed.id)
                true
            }
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
            journaled {
                journal.deleteConversation(accountRef, groupIdHex)
                true
            }
        }
    }

    /** Forgets every operation of a signed-out account. */
    fun forgetAccount(accountRef: String) {
        synchronized(lock) {
            state.update { current -> current.filterValues { it.accountRef != accountRef } }
            journaled {
                journal.deleteAccount(accountRef)
                true
            }
        }
    }

    /**
     * Journaled operations of one conversation that this process does not already run, with expired
     * records swept first, read after every queued effect. Blocks, so it is called off the main thread.
     */
    fun journaledFor(
        accountRef: String,
        groupIdHex: String,
    ): List<AttachmentCollectionOperation> {
        val records =
            JOURNAL_EFFECTS
                .submit(
                    Callable {
                        journal.sweepExpired()
                        journal.read(accountRef, groupIdHex)
                    },
                ).get()
        val live = state.value.keys
        return records.filter { it.id !in live }
    }

    /** Adopts a restored operation without rewriting its record until it next changes. */
    fun adopt(operation: AttachmentCollectionOperation) {
        synchronized(lock) {
            state.update { current ->
                if (operation.id in current) current else current + (operation.id to operation)
            }
        }
    }
}

// One thread for every registry's journal effects, so each registry's effects run in the order it queued them.
private val JOURNAL_EFFECTS =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "attachment-collection-journal").apply { isDaemon = true }
    }

/** Queues [effect] behind every earlier one; a failure counts as not recorded. */
private fun journaled(effect: () -> Boolean): Future<Boolean> {
    val task = Callable { runCatching(effect).getOrDefault(false) }
    return JOURNAL_EFFECTS.submit(task)
}

/** Waits until every journal effect queued so far has run; tests use it before reading the files. */
internal fun awaitAttachmentCollectionJournal() {
    journaled { true }.get()
}

private val resumingCollections = mutableSetOf<String>()

/** Claims the resume of [operationId]; false while another resume of it is still in progress. */
internal fun claimCollectionResume(operationId: String): Boolean {
    synchronized(resumingCollections) {
        return resumingCollections.add(operationId)
    }
}

/** Ends a resume claim once the operation is no longer paused, or the resume gave up. */
internal fun releaseCollectionResume(operationId: String) {
    synchronized(resumingCollections) { resumingCollections.remove(operationId) }
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
