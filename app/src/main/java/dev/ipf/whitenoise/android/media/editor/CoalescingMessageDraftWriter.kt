package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MessageDraftGeneration(
    val value: Long,
)

/** Coalesces composer keystrokes while the repository serializes them with attachment edits. */
internal class CoalescingMessageDraftWriter(
    private val scope: CoroutineScope,
    private val drafts: MessageDraftRepository,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onResult: (String, String, String, MessageDraftMutationResult) -> Unit = { _, _, _, _ -> },
) {
    private val lock = Any()
    private val pending = mutableMapOf<Key, Pending>()
    private val acceptedGenerations = mutableMapOf<Key, Long>()
    private val activeMerges = mutableMapOf<Key, ActiveMerge>()
    private val mergeLocks = mutableMapOf<Key, Mutex>()

    fun submit(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ): MessageDraftGeneration {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            val state = pending.getOrPut(key) { Pending(generation = acceptedGenerations[key] ?: 0) }
            state.content = activeMerges[key]?.let { merge -> mergeDraftText(content, merge.incoming) } ?: content
            state.generation++
            acceptedGenerations[key] = state.generation
            if (state.job == null) state.job = scope.launch { drain(key, state) }
            MessageDraftGeneration(state.generation)
        }
    }

    fun generation(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftGeneration =
        synchronized(lock) {
            MessageDraftGeneration(acceptedGenerations[Key(accountRef, groupIdHex)] ?: 0)
        }

    fun isCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Boolean =
        synchronized(lock) {
            (acceptedGenerations[Key(accountRef, groupIdHex)] ?: 0) == generation.value
        }

    suspend fun flush() {
        while (true) {
            val jobs = synchronized(lock) { pending.values.mapNotNull(Pending::job) }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    suspend fun loadIfCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Result<MessageDraftFfi?>? {
        val key = Key(accountRef, groupIdHex)
        flush(key)
        return drafts.conditional.draftIf(accountRef, groupIdHex) {
            isCurrent(accountRef, groupIdHex, generation)
        }
    }

    suspend fun deleteIfCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): MessageDraftConditionalDeleteResult {
        val key = Key(accountRef, groupIdHex)
        flush(key)
        return drafts.conditional.deleteIf(accountRef, groupIdHex) {
            isCurrent(accountRef, groupIdHex, generation)
        }
    }

    suspend fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ): MessageDraftMutationResult {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) return drafts.mergeText(accountRef, groupIdHex, incoming)
        val key = Key(accountRef, groupIdHex)
        val mergeLock = synchronized(lock) { mergeLocks.getOrPut(key) { Mutex() } }
        return mergeLock.withLock {
            val activeMerge = ActiveMerge(trimmedIncoming)
            beginMerge(key, activeMerge)
            try {
                val mergeResult = drafts.mergeText(accountRef, groupIdHex, trimmedIncoming)
                finishMerge(key, activeMerge, mergeResult)
            } finally {
                synchronized(lock) { activeMerges.remove(key, activeMerge) }
            }
        }
    }

    private suspend fun beginMerge(
        key: Key,
        activeMerge: ActiveMerge,
    ) {
        while (true) {
            val pendingJob =
                synchronized(lock) {
                    pending[key]?.job.also { job ->
                        if (job == null) activeMerges[key] = activeMerge
                    }
                }
            if (pendingJob == null) return
            pendingJob.join()
        }
    }

    private suspend fun finishMerge(
        key: Key,
        activeMerge: ActiveMerge,
        mergeResult: MessageDraftMutationResult,
    ): MessageDraftMutationResult {
        while (true) {
            flush(key)
            val completed =
                synchronized(lock) {
                    if (pending[key]?.job == null) {
                        true
                    } else {
                        false
                    }
                }
            if (completed) return activeMerge.latestResult ?: mergeResult
        }
    }

    private suspend fun flush(key: Key) {
        while (true) {
            val job = synchronized(lock) { pending[key]?.job } ?: return
            job.join()
        }
    }

    private suspend fun drain(
        key: Key,
        state: Pending,
    ) {
        delay(debounceMillis)
        while (true) {
            val (content, generation) = synchronized(lock) { state.content to state.generation }
            val result = drafts.saveText(key.accountRef, key.groupIdHex, content)
            val isLatest =
                synchronized(lock) {
                    activeMerges[key]?.latestResult = result
                    acceptedGenerations[key] == generation
                }
            if (isLatest) runCatching { onResult(key.accountRef, key.groupIdHex, content, result) }
            val caughtUp =
                synchronized(lock) {
                    if (state.generation == generation) {
                        state.job = null
                        pending.remove(key, state)
                        true
                    } else {
                        false
                    }
                }
            if (caughtUp) return
        }
    }

    private data class Key(
        val accountRef: String,
        val groupIdHex: String,
    )

    private class Pending(
        var content: String = "",
        var generation: Long = 0,
        var job: Job? = null,
    )

    private class ActiveMerge(
        val incoming: String,
        var latestResult: MessageDraftMutationResult? = null,
    )

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
    }
}

private fun mergeDraftText(
    existing: String,
    incoming: String,
): String =
    if (existing.isBlank()) {
        incoming
    } else {
        "${existing.trimEnd()}\n$incoming"
    }
