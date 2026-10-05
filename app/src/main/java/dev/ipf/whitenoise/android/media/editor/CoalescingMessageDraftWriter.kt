package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftFfi
import kotlinx.coroutines.CancellationException
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
    val generation: MessageDraftGeneration? = null,
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
            if (isHydrationBlocked(key, generation)) return@synchronized false
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

    /** Flush accepted edits, retry each retained failure once, and report unsaved text before deactivation. */
    suspend fun flushAccount(accountRef: String): Boolean {
        val retried = mutableSetOf<Key>()
        while (true) {
            val jobs =
                synchronized(lock) {
                    pending
                        .filterKeys { it.accountRef == accountRef }
                        .mapNotNull { (key, state) ->
                            if (
                                state.job == null &&
                                state.lastResult is MessageDraftMutationResult.Failure &&
                                retried.add(key)
                            ) {
                                state.job = scope.launch { drain(key, state) }
                            }
                            state.job
                        }
                }
            if (jobs.isEmpty()) {
                return synchronized(lock) {
                    pending.none { (key, state) ->
                        key.accountRef == accountRef && state.lastResult is MessageDraftMutationResult.Failure
                    }
                }
            }
            jobs.forEach { it.join() }
        }
    }

    /** Account removal retires unsaved private text and invalidates late saves/hydration without a native write. */
    fun removeAccount(accountRef: String) {
        val jobs =
            synchronized(lock) {
                val keys =
                    (pending.keys + activeMerges.keys + hydrationBlockedGenerations.keys)
                        .filter { it.accountRef == accountRef }
                        .toSet()
                keys.mapNotNull { key ->
                    drafts.coordinated.acceptMutation(key.accountRef, key.groupIdHex)
                    activeMerges.remove(key)
                    hydrationBlockedGenerations.remove(key)
                    pending.remove(key)?.job
                }
            }
        jobs.forEach { it.cancel() }
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

    /** Flush accepted edits before importing text, retaining exact retry identity and a generation-fenced UI result. */
    suspend fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
        trimIncoming: Boolean = true,
        receipt: MessageDraftMergeReceipt? = null,
    ): MessageDraftMergeCompletion {
        val trimmedIncoming = if (trimIncoming) incoming.trim() else incoming
        if (trimmedIncoming.isEmpty()) {
            return MessageDraftMergeCompletion(
                result = MessageDraftMutationResult.Success(draft = null),
                contentForHydration = null,
                draftedAtMs = null,
            )
        }
        val key = Key(accountRef, groupIdHex)
        val mergeLock = synchronized(lock) { mergeLocks.getOrPut(key) { Mutex() } }
        return mergeLock.withLock {
            val activeMerge = ActiveMerge(trimmedIncoming, receipt)
            val flushFailure = beginMerge(key, activeMerge)
            if (flushFailure != null) {
                MessageDraftMergeCompletion(flushFailure, null, null)
            } else {
                try {
                    val mergeResult =
                        drafts.coordinated.mergeAcceptedText(accountRef, groupIdHex, trimmedIncoming, receipt)
                    finishMerge(key, activeMerge, mergeResult)
                } finally {
                    synchronized(lock) { activeMerges.remove(key, activeMerge) }
                }
            }
        }
    }

    /** Drain old keystrokes before advancing generation; a failed drain leaves their text available for retry. */
    private suspend fun beginMerge(
        key: Key,
        activeMerge: ActiveMerge,
    ): MessageDraftMutationResult.Failure? {
        while (true) {
            val (state, pendingJob) =
                synchronized(lock) {
                    val state = pending[key]
                    if (state != null && state.job == null && state.lastResult is MessageDraftMutationResult.Failure) {
                        state.job = scope.launch { drain(key, state) }
                    }
                    val job = state?.job
                    if (job == null) {
                        activeMerges[key] = activeMerge
                        drafts.coordinated.acceptMutation(key.accountRef, key.groupIdHex)
                        hydrationBlockedGenerations.remove(key)
                    }
                    state to job
                }
            if (pendingJob == null) return null
            pendingJob.join()
            val failure =
                synchronized(lock) {
                    if (pending[key] === state) state?.lastResult as? MessageDraftMutationResult.Failure else null
                }
            if (failure != null) return failure
        }
    }

    /** Snapshot the last accepted content and generation together after edits concurrent with the import finish. */
    private suspend fun finishMerge(
        key: Key,
        activeMerge: ActiveMerge,
        mergeResult: MessageDraftMutationResult,
    ): MessageDraftMergeCompletion {
        while (true) {
            flush(key)
            val completed =
                synchronized(lock) {
                    if (activeMerges[key] !== activeMerge) {
                        throw CancellationException("Draft merge retired with its account")
                    }
                    if (pending[key]?.job == null) {
                        mergeCompletion(activeMerge.latestResult, activeMerge.latestContent, mergeResult)
                            .copy(generation = drafts.coordinated.generation(key.accountRef, key.groupIdHex))
                    } else {
                        null
                    }
                }
            if (completed != null) return completed
        }
    }

    private suspend fun flush(key: Key) {
        while (true) {
            val job = synchronized(lock) { pending[key]?.job } ?: return
            job.join()
        }
    }

    /**
     * Persists the coalesced text for [key] after the debounce interval, retrying while newer
     * keystrokes keep arriving.
     *
     * The save is conditional on the generation this pass captured. A send that durably
     * completes inside the debounce window advances the generation as part of its cleanup, and
     * writing anyway would restore the draft that cleanup deleted, putting the sent text back
     * into the composer.
     *
     * A superseded pass therefore retires the queue entry only when nothing newer is waiting.
     * The generation also moves when a keystroke lands between this pass capturing its content
     * and the store checking currency, and that edit still has to reach storage, so the loop
     * repeats for it exactly as it does after an applied save.
     */
    private suspend fun drain(
        key: Key,
        state: Pending,
    ) {
        delay(debounceMillis)
        while (true) {
            val (content, generation) =
                synchronized(lock) {
                    activeMerges[key]?.receipt?.proposedContent = state.content
                    state.content to state.generation
                }
            val saved =
                drafts.coordinated.saveAcceptedTextIfCurrent(
                    accountRef = key.accountRef,
                    groupIdHex = key.groupIdHex,
                    content = content,
                    generation = MessageDraftGeneration(generation),
                )
            if (saved is MessageDraftConditionalSaveResult.Superseded) {
                val retired =
                    synchronized(lock) {
                        if (state.generation == generation) {
                            state.job = null
                            pending.remove(key, state)
                            true
                        } else {
                            false
                        }
                    }
                if (retired) return
                continue
            }
            val result = (saved as MessageDraftConditionalSaveResult.Applied).result
            val isLatest =
                synchronized(lock) {
                    activeMerges[key]?.latestResult = result
                    activeMerges[key]?.latestContent = content
                    state.lastResult = result
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
                        if (result !is MessageDraftMutationResult.Failure) pending.remove(key, state)
                        true
                    } else {
                        false
                    }
                }
            if (caughtUp) return
        }
    }

    /** Coalesce newer edits with an active import while preserving their accepted generation and retryable text. */
    private fun enqueueAccepted(
        key: Key,
        content: String,
        generation: MessageDraftGeneration,
    ) {
        val state = pending.getOrPut(key) { Pending() }
        state.lastResult = null
        val merge = activeMerges[key]
        state.content = merge?.let { mergeDraftText(content, it.incoming) } ?: content
        state.generation = generation.value
        if (state.job == null) state.job = scope.launch { drain(key, state) }
    }

    private fun isHydrationBlocked(
        key: Key,
        generation: MessageDraftGeneration,
    ): Boolean =
        synchronized(lock) {
            val failed = pending[key]?.takeIf { it.lastResult is MessageDraftMutationResult.Failure }
            hydrationBlockedGenerations[key] == generation || failed?.generation == generation.value
        }

    private data class Key(
        val accountRef: String,
        val groupIdHex: String,
    )

    private class Pending(
        var content: String = "",
        // staleness-exempt: captured accepted draft-mutation token, not a counter owner.
        var generation: Long = 0L,
        var job: Job? = null,
        var lastResult: MessageDraftMutationResult? = null,
    )

    private class ActiveMerge(
        val incoming: String,
        val receipt: MessageDraftMergeReceipt?,
        var latestResult: MessageDraftMutationResult? = null,
        var latestContent: String? = null,
    )

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 250L
    }
}

/**
 * Builds the merge result from the persisted draft and the latest accepted local edit.
 * The reported ordering time follows `updatedAtMs`, because an edit must sort by its
 * accepted update rather than by the draft's original creation time.
 */
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
