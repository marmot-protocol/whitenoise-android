package dev.ipf.whitenoise.android.media.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    fun submit(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ) {
        val key = Key(accountRef, groupIdHex)
        synchronized(lock) {
            val state = pending.getOrPut(key) { Pending(generation = acceptedGenerations[key] ?: 0) }
            state.content = content
            state.generation++
            acceptedGenerations[key] = state.generation
            if (state.job == null) state.job = scope.launch { drain(key, state) }
        }
    }

    suspend fun flush() {
        while (true) {
            val jobs = synchronized(lock) { pending.values.mapNotNull(Pending::job) }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
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
            val isLatest = synchronized(lock) { acceptedGenerations[key] == generation }
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

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
    }
}
