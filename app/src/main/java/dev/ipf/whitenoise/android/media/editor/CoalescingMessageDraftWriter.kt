package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MessageDraftMergeCompletion(
    val result: MessageDraftMutationResult,
    val contentForHydration: String?,
    val draftedAtMs: Long?,
)

/** Coalesces composer keystrokes while the repository serializes them with attachment edits. */
@Suppress("TooManyFunctions")
internal class CoalescingMessageDraftWriter(
    private val scope: CoroutineScope,
    private val drafts: MessageDraftRepository,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onResult: (String, String, String, MessageDraftMutationResult) -> Unit = { _, _, _, _ -> },
) {
    private val lock = Any()
    private val pending = mutableMapOf<Key, Pending>()
    private val activeMerges = mutableMapOf<Key, ActiveMerge>()
    private val mergeLocks = mutableMapOf<Key, Mutex>()
    private val hydrationBlockedGenerations = mutableMapOf<Key, MessageDraftGeneration>()

    fun submit(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ): MessageDraftGeneration {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            val generation = drafts.coordinated.acceptMutation(accountRef, groupIdHex)
            hydrationBlockedGenerations.remove(key)
            enqueueAccepted(key, content, generation)
            generation
        }
    }

    /**
     * Accepts [content] only while [expected] is still the authoritative
     * generation for this account/group. This is the compare-and-set boundary
     * used by delayed producers such as speech recognition: a draft edit or an
     * attachment mutation that lands after their read makes the write fail
     * closed instead of overwriting newer state.
     */
    fun submitIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expected: MessageDraftGeneration,
        content: String,
    ): MessageDraftGeneration? {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            val generation =
                drafts.coordinated.acceptMutationIfCurrent(accountRef, groupIdHex, expected)
                    ?: return@synchronized null
            hydrationBlockedGenerations.remove(key)
            enqueueAccepted(key, content, generation)
            generation
        }
    }

    fun generation(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftGeneration = drafts.coordinated.generation(accountRef, groupIdHex)

    fun isCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Boolean = drafts.coordinated.isCurrent(accountRef, groupIdHex, generation)

    /**
     * Claims the lifecycle presentation of an optimistic send without deleting
     * its durable MDK recovery draft. Re-entry hydration for the captured
     * generation stays blocked until durable cleanup or a newer mutation wins.
     */
    fun beginPendingSendPresentation(
        accountRef: String,
        groupIdHex: String,
        sentGeneration: MessageDraftGeneration,
        onClaimed: () -> Unit = {},
    ): Boolean {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            if (!drafts.coordinated.isCurrent(accountRef, groupIdHex, sentGeneration)) {
                return@synchronized false
            }
            hydrationBlockedGenerations[key] = sentGeneration
            onClaimed()
            true
        }
    }

    /**
     * Claims successful-send cleanup as a new generation before Android clears
     * its lifecycle projection. Any MDK hydration that started against the
     * sent generation then fails its post-read currency check and cannot put
     * the accepted text back into the composer or chat row. The cleanup
     * generation remains hydration-blocked until deletion succeeds or a newer
     * mutation supersedes it (#2225).
     */
    fun beginSuccessfulSendCleanup(
        accountRef: String,
        groupIdHex: String,
        sentGeneration: MessageDraftGeneration,
        onClaimed: () -> Unit = {},
    ): MessageDraftGeneration? {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            val cleanupGeneration =
                drafts.coordinated.acceptMutationIfCurrent(accountRef, groupIdHex, sentGeneration)
                    ?: return@synchronized null
            hydrationBlockedGenerations[key] = cleanupGeneration
            onClaimed()
            cleanupGeneration
        }
    }

    fun runIfCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        block: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (!drafts.coordinated.isCurrent(accountRef, groupIdHex, generation)) return@synchronized false
            block()
            true
        }

    /** Commit a completed authoritative read only while its generation remains visible. */
    fun runHydrationIfCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
        block: () -> Unit,
    ): Boolean {
        val key = Key(accountRef, groupIdHex)
        return synchronized(lock) {
            if (hydrationBlockedGenerations[key] == generation) return@synchronized false
            if (!drafts.coordinated.isCurrent(accountRef, groupIdHex, generation)) return@synchronized false
            block()
            true
        }
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
        val blockedBeforeFlush = isHydrationBlocked(key, generation)
        if (!blockedBeforeFlush) flush(key)
        return if (blockedBeforeFlush || isHydrationBlocked(key, generation)) {
            null
        } else {
            drafts.coordinated.draftIf(accountRef, groupIdHex, generation)
        }
    }

    suspend fun deleteIfCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): MessageDraftConditionalDeleteResult {
        val key = Key(accountRef, groupIdHex)
        flush(key)
        val deletion = drafts.coordinated.deleteIf(accountRef, groupIdHex, generation)
        if (deletion is MessageDraftConditionalDeleteResult.Applied &&
            deletion.result is MessageDraftMutationResult.Success
        ) {
            synchronized(lock) {
                hydrationBlockedGenerations.remove(key, generation)
            }
        }
        return deletion
    }

    suspend fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ): MessageDraftMergeCompletion {
        val trimmedIncoming = incoming.trim()
        if (trimmedIncoming.isEmpty()) {
            return MessageDraftMergeCompletion(
                result = MessageDraftMutationResult.Success(draft = null),
                contentForHydration = null,
                draftedAtMs = null,
            )
        }
        val key = Key(accountRef, groupIdHex)
        synchronized(lock) {
            drafts.coordinated.acceptMutation(accountRef, groupIdHex)
            hydrationBlockedGenerations.remove(key)
        }
        val mergeLock = synchronized(lock) { mergeLocks.getOrPut(key) { Mutex() } }
        return mergeLock.withLock {
            val activeMerge = ActiveMerge(trimmedIncoming)
            beginMerge(key, activeMerge)
            try {
                val mergeResult = drafts.coordinated.mergeAcceptedText(accountRef, groupIdHex, trimmedIncoming)
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
    ): MessageDraftMergeCompletion {
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
            if (completed) {
                return mergeCompletion(activeMerge.latestResult, activeMerge.latestContent, mergeResult)
            }
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
            val result = drafts.coordinated.saveAcceptedText(key.accountRef, key.groupIdHex, content)
            val isLatest =
                synchronized(lock) {
                    activeMerges[key]?.latestResult = result
                    activeMerges[key]?.latestContent = content
                    drafts.coordinated.isCurrent(
                        key.accountRef,
                        key.groupIdHex,
                        MessageDraftGeneration(generation),
                    )
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

    private fun enqueueAccepted(
        key: Key,
        content: String,
        generation: MessageDraftGeneration,
    ) {
        val state = pending.getOrPut(key) { Pending() }
        state.content = activeMerges[key]?.let { merge -> mergeDraftText(content, merge.incoming) } ?: content
        state.generation = generation.value
        if (state.job == null) state.job = scope.launch { drain(key, state) }
    }

    private fun isHydrationBlocked(
        key: Key,
        generation: MessageDraftGeneration,
    ): Boolean = synchronized(lock) { hydrationBlockedGenerations[key] == generation }

    private data class Key(
        val accountRef: String,
        val groupIdHex: String,
    )

    private class Pending(
        var content: String = "",
        // staleness-exempt: captured accepted draft-mutation token, not a counter owner.
        var generation: Long = 0L,
        var job: Job? = null,
    )

    private class ActiveMerge(
        val incoming: String,
        var latestResult: MessageDraftMutationResult? = null,
        var latestContent: String? = null,
    )

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
    }
}

private fun mergeCompletion(
    latestResult: MessageDraftMutationResult?,
    latestContent: String?,
    mergeResult: MessageDraftMutationResult,
): MessageDraftMergeCompletion {
    val result = latestResult ?: mergeResult
    val savedDraft = (result as? MessageDraftMutationResult.Success)?.draft
    val mergedDraft = (mergeResult as? MessageDraftMutationResult.Success)?.draft
    return MessageDraftMergeCompletion(
        result = result,
        contentForHydration = savedDraft?.content ?: latestContent ?: mergedDraft?.content,
        draftedAtMs = savedDraft?.updatedAtMs ?: mergedDraft?.updatedAtMs,
    )
}

internal fun mergeDraftText(
    existing: String,
    incoming: String,
): String =
    if (existing.isBlank()) {
        incoming
    } else {
        "${existing.trimEnd()}\n$incoming"
    }
