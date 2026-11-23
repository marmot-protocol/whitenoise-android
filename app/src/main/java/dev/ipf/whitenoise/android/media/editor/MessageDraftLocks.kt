package dev.ipf.whitenoise.android.media.editor

import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DraftKey(
    val accountRef: String,
    val groupIdHex: String,
)

internal class MessageDraftLocks {
    private val tableGuard = Any()
    private val table = mutableMapOf<DraftKey, DraftLock>()

    /** Runs [block] holding [key]'s lock, dropping the lock entry once no caller waits on it. */
    suspend fun <T> withLock(
        key: DraftKey,
        block: suspend () -> T,
    ): T {
        val entry =
            synchronized(tableGuard) {
                table.getOrPut(key) { DraftLock() }.also { it.users += 1 }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(tableGuard) {
                entry.users -= 1
                if (entry.users == 0 && table[key] === entry) table.remove(key)
            }
        }
    }

    private class DraftLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}

internal class MessageDraftMutationGenerations {
    private val lock = Any()
    private val lifetimes = mutableMapOf<DraftKey, StalenessGuard>()

    // The newest generation per key that was not an attachment-only mutation; text accepted before it is stale.
    private val textFences = mutableMapOf<DraftKey, Long>()

    /**
     * Accepts a new mutation for [key] and returns its guarded generation. With [fencesText] false, for a mutation that
     * changes only attachments, it still supersedes send cleanup and hydration captured before it, but text already
     * accepted stays current and is still written.
     */
    fun advance(
        key: DraftKey,
        fencesText: Boolean = true,
    ): MessageDraftGeneration =
        synchronized(lock) {
            val generation = lifetime(key).advance()
            if (fencesText) textFences[key] = generation
            MessageDraftGeneration(generation)
        }

    /** Atomically accepts a mutation only while [expected] still owns [key]. */
    fun advanceIfCurrent(
        key: DraftKey,
        expected: MessageDraftGeneration,
    ): MessageDraftGeneration? =
        synchronized(lock) {
            lifetime(key).advanceIfCurrent(expected.value)?.also { textFences[key] = it }?.let(::MessageDraftGeneration)
        }

    /** Captures the mutation generation currently visible for [key]. */
    fun current(key: DraftKey) = synchronized(lock) { MessageDraftGeneration(lifetime(key).capture()) }

    /** Reports whether [generation] remains the newest accepted mutation for [key]. */
    fun isCurrent(
        key: DraftKey,
        generation: MessageDraftGeneration,
    ): Boolean = synchronized(lock) { lifetime(key).isCurrent(generation.value) }

    /**
     * Reports whether text accepted at [generation] may still be written: no mutation has landed since it except
     * attachment-only ones.
     */
    fun isTextCurrent(
        key: DraftKey,
        generation: MessageDraftGeneration,
    ): Boolean =
        synchronized(lock) {
            generation.value >= (textFences[key] ?: 0L) && generation.value <= lifetime(key).capture()
        }

    /** Returns the single staleness primitive assigned to one draft key. */
    private fun lifetime(key: DraftKey): StalenessGuard = lifetimes.getOrPut(key, ::StalenessGuard)
}
