package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only Android-side mutation boundary for hydrated MDK message drafts.
 *
 * Mutations for one account/group are serialized. Each mutation re-reads the
 * latest draft under that lock and writes exactly once, preserving fields it
 * does not own. Image replacement additionally guards the stable attachment id
 * with a digest, so an editor opened against stale bytes fails closed.
 */
internal class MessageDraftRepository(
    private val gateway: MessageDraftGateway,
    private val editorSessions: EditorSessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val draftLocks = MessageDraftLocks()
    private val mutationGenerations = MessageDraftMutationGenerations()
    internal val coordinated =
        MessageDraftCoordinatedOperations(
            gateway = gateway,
            draftLocks = draftLocks,
            mutationGenerations = mutationGenerations,
            ioDispatcher = ioDispatcher,
        )

    /** File-backed attachments, under the same per-draft locks and generations as every other mutation. */
    internal val files = MessageDraftFiles(gateway, draftLocks, mutationGenerations, ioDispatcher)

    @Suppress("TooGenericExceptionCaught") // FFI gateway failures are returned; cancellation is rethrown first.
    suspend fun draft(
        accountRef: String,
        groupIdHex: String,
    ): Result<MessageDraftFfi?> =
        withContext(ioDispatcher) {
            try {
                Result.success(gateway.read(accountRef, groupIdHex))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }

    @Suppress("TooGenericExceptionCaught") // FFI failures are returned; cancellation must retain structured semantics.
    suspend fun summaries(accountRef: String): Result<List<MessageDraftSummaryFfi>> =
        withContext(ioDispatcher) {
            try {
                Result.success(gateway.summaries(accountRef))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }

    suspend fun saveText(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            saveDraftText(gateway, accountRef, groupIdHex, content)
        }

    /** Appends [incoming] to the draft text on a new line, keeping its reply and attachments. */
    suspend fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            val trimmedIncoming = incoming.trim()
            val base = gateway.readRevisioned(accountRef, groupIdHex)
            if (trimmedIncoming.isEmpty()) return@mutate MessageDraftMutationResult.Success(base.draft)
            val current = base.draft
            val merged =
                if (current?.content.isNullOrBlank()) {
                    trimmedIncoming
                } else {
                    "${current.content.trimEnd()}\n$trimmedIncoming"
                }
            saveDraftText(gateway, accountRef, groupIdHex, merged, base)
        }

    /** Appends [attachment] inline; an id the draft already holds is a duplicate. */
    suspend fun addAttachment(
        accountRef: String,
        groupIdHex: String,
        attachment: MessageDraftAttachmentFfi,
        pendingSession: EditorAttachmentSession? = null,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex, attachmentOnly = true) {
            val base = gateway.readRevisioned(accountRef, groupIdHex)
            val current = base.draft
            if (current?.mediaAttachments.orEmpty().any { it.id == attachment.id }) {
                return@mutate MessageDraftMutationResult.DuplicateAttachment
            }
            if (pendingSession != null && !pendingSession.matches(accountRef, groupIdHex, attachment)) {
                return@mutate MessageDraftMutationResult.InvalidEditorSession
            }
            commitDraftAttachmentMutation(
                gateway = gateway,
                editorSessions = editorSessions,
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                base = base,
                updatedAttachments = current?.mediaAttachments.orEmpty() + attachment,
                changedAttachment = attachment,
                pendingSession = pendingSession,
                previousSession = null,
            )
        }

    /** Replaces one attachment in place while it still matches [expectedDigest]. */
    suspend fun replaceAttachment(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        expectedDigest: String,
        replacement: MessageDraftAttachmentFfi,
        pendingSession: EditorAttachmentSession,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex, attachmentOnly = true) {
            if (replacement.id != attachmentId ||
                !pendingSession.matches(accountRef, groupIdHex, replacement)
            ) {
                return@mutate MessageDraftMutationResult.InvalidEditorSession
            }
            val base = gateway.readRevisioned(accountRef, groupIdHex)
            val current = base.draft
            val currentAttachments =
                current?.mediaAttachments
                    ?: return@mutate MessageDraftMutationResult.MissingAttachment
            val targetIndex = currentAttachments.indexOfFirst { it.id == attachmentId }
            if (targetIndex < 0) return@mutate MessageDraftMutationResult.MissingAttachment
            val target = currentAttachments[targetIndex]
            if (target.editorDigest() != expectedDigest) {
                return@mutate MessageDraftMutationResult.StaleAttachment
            }
            val previousSession =
                editorSessions.committed(accountRef, groupIdHex, attachmentId, expectedDigest)
            val updated = currentAttachments.toMutableList().apply { this[targetIndex] = replacement }
            commitDraftAttachmentMutation(
                gateway = gateway,
                editorSessions = editorSessions,
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                base = base,
                updatedAttachments = updated,
                changedAttachment = replacement,
                pendingSession = pendingSession,
                previousSession = previousSession,
            )
        }

    /** Removes one attachment, deleting the draft when nothing else remains in it. */
    @Suppress("TooGenericExceptionCaught") // FFI gateways can surface unchecked failures; cancellation is rethrown.
    suspend fun removeAttachment(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        expectedDigest: String? = null,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex, attachmentOnly = true) {
            val base = gateway.readRevisioned(accountRef, groupIdHex)
            val current = base.draft
            val attachments = current?.mediaAttachments ?: return@mutate MessageDraftMutationResult.MissingAttachment
            val target =
                attachments.firstOrNull { it.id == attachmentId }
                    ?: return@mutate MessageDraftMutationResult.MissingAttachment
            if (expectedDigest != null && target.editorDigest() != expectedDigest) {
                return@mutate MessageDraftMutationResult.StaleAttachment
            }
            val updated = attachments.filterNot { it.id == attachmentId }
            val deleteEmptyDraft = updated.isEmpty() && current.content.isBlank() && current.replyToMessageIdHex == null
            try {
                val saved =
                    if (deleteEmptyDraft) {
                        gateway.clearRevisioned(accountRef, groupIdHex, base)
                        null
                    } else {
                        gateway.saveRevisioned(
                            accountRef,
                            groupIdHex,
                            base,
                            MessageDraftWrite(current.content, current.replyToMessageIdHex, updated),
                        )
                    }
                val removedSession = editorSessions.remove(accountRef, groupIdHex, attachmentId)
                MessageDraftMutationResult.Success(saved, previousEditorSession = removedSession)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                val rereadResult = authoritativeDraft(gateway, accountRef, groupIdHex)
                val reread = rereadResult.getOrNull()
                val committed =
                    if (deleteEmptyDraft) {
                        rereadResult.isSuccess && reread == null
                    } else {
                        reread?.mediaAttachments?.none { it.id == attachmentId } == true
                    }
                if (committed) {
                    val removedSession = editorSessions.remove(accountRef, groupIdHex, attachmentId)
                    MessageDraftMutationResult.Success(reread, previousEditorSession = removedSession)
                } else {
                    MessageDraftMutationResult.Failure(cause)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // FFI gateway failures are resolved against an authoritative re-read.
    suspend fun delete(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            deleteDraft(gateway, accountRef, groupIdHex)
        }

    /**
     * Repairs pending adjunct records against authoritative MDK attachment
     * bytes, then makes encrypted source ownership exactly match live sessions.
     * Any MDK read failure leaves both stores untouched and retries next start.
     */
    @Suppress("TooGenericExceptionCaught") // Reconciliation is retryable for all non-cancellation gateway failures.
    suspend fun reconcileEditorState(sources: EditorSourceStore): Result<Int> =
        withContext(ioDispatcher) {
            try {
                val sessionKeys =
                    editorSessions.attachmentKeys()
                        ?: return@withContext Result.failure(editorSessionStoreUnavailable())
                val committedDigests = linkedMapOf<Triple<String, String, String>, String>()
                sessionKeys
                    .groupBy { it.first to it.second }
                    .forEach { (accountGroup, keys) ->
                        val draft = gateway.read(accountGroup.first, accountGroup.second)
                        val requestedIds = keys.mapTo(hashSetOf()) { it.third }
                        draft
                            ?.mediaAttachments
                            .orEmpty()
                            .filter { it.id in requestedIds }
                            .forEach { attachment ->
                                committedDigests[
                                    Triple(accountGroup.first, accountGroup.second, attachment.id),
                                ] = attachment.editorDigest()
                            }
                    }
                if (editorSessions.reconcile(committedDigests) == null) {
                    return@withContext Result.failure(editorSessionStoreUnavailable())
                }
                val sourceLeaseReferences =
                    editorSessions.sourceLeaseReferenceCounts()
                        ?: return@withContext Result.failure(editorSessionStoreUnavailable())
                val reconciledSources =
                    sources.reconcile(sourceLeaseReferences)
                        ?: return@withContext Result.failure(editorSourceStoreUnavailable())
                Result.success(reconciledSources)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }

    /**
     * Runs one mutation under the draft's lock, re-running it once after a lost revision race. An [attachmentOnly]
     * mutation leaves text that was accepted before it current, so a queued text save still lands.
     */
    private suspend fun mutate(
        accountRef: String,
        groupIdHex: String,
        attachmentOnly: Boolean = false,
        block: suspend () -> MessageDraftMutationResult,
    ): MessageDraftMutationResult {
        val key = DraftKey(accountRef, groupIdHex)
        return withContext(ioDispatcher) {
            draftLocks.withLock(key) {
                // A lost revision race re-runs the block once: it re-reads the fresh selected draft under
                // the same lock, so the user's newest edit is applied on top of the other writer's state.
                var result = runDraftMutation(block)
                if (result.isRevisionConflict()) result = runDraftMutation(block)
                if (result is MessageDraftMutationResult.Success) {
                    mutationGenerations.advance(key, fencesText = !attachmentOnly)
                }
                result
            }
        }
    }
}

internal class MessageDraftCoordinatedOperations(
    private val gateway: MessageDraftGateway,
    private val draftLocks: MessageDraftLocks,
    private val mutationGenerations: MessageDraftMutationGenerations,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun acceptMutation(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftGeneration = mutationGenerations.advance(DraftKey(accountRef, groupIdHex))

    fun acceptMutationIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expected: MessageDraftGeneration,
    ): MessageDraftGeneration? =
        mutationGenerations.advanceIfCurrent(
            key = DraftKey(accountRef, groupIdHex),
            expected = expected,
        )

    fun generation(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftGeneration = mutationGenerations.current(DraftKey(accountRef, groupIdHex))

    fun isCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Boolean = mutationGenerations.isCurrent(DraftKey(accountRef, groupIdHex), generation)

    /** Whether text accepted at [generation] may still be written; only attachment-only mutations have landed since. */
    fun isTextCurrent(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Boolean = mutationGenerations.isTextCurrent(DraftKey(accountRef, groupIdHex), generation)

    /**
     * Writes [content] only while [generation] is still authoritative for this account and
     * group, making the same currency check [deleteIf] and [draftIf] already make.
     *
     * The coalescing writer debounces keystrokes, so a queued save can wake after the send
     * carrying its text has durably completed. Successful-send cleanup advances the
     * generation before deleting MDK's draft, so an unguarded write at that point re-creates
     * the row cleanup just removed and the sent text returns to the composer on the next
     * hydration. Reporting the supersession lets the writer retire that queue entry instead.
     */
    suspend fun saveAcceptedTextIfCurrent(
        accountRef: String,
        groupIdHex: String,
        content: String,
        generation: MessageDraftGeneration,
    ): MessageDraftConditionalSaveResult =
        withContext(ioDispatcher) {
            val key = DraftKey(accountRef, groupIdHex)
            draftLocks.withLock(key) {
                if (!mutationGenerations.isTextCurrent(key, generation)) {
                    return@withLock MessageDraftConditionalSaveResult.Superseded
                }
                // A lost revision race, such as a send saving its caption, re-reads and saves once more while the
                // text is still current; a pass superseded meanwhile does not.
                var result = runDraftMutation { saveDraftText(gateway, accountRef, groupIdHex, content) }
                if (result.isRevisionConflict() && mutationGenerations.isTextCurrent(key, generation)) {
                    result = runDraftMutation { saveDraftText(gateway, accountRef, groupIdHex, content) }
                }
                MessageDraftConditionalSaveResult.Applied(result)
            }
        }

    /** Merges [incoming] once per [receipt], so a retried merge does not repeat the text. */
    suspend fun mergeAcceptedText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
        receipt: MessageDraftMergeReceipt? = null,
    ): MessageDraftMutationResult =
        withContext(ioDispatcher) {
            draftLocks.withLock(DraftKey(accountRef, groupIdHex)) {
                runDraftMutation {
                    val base = gateway.readRevisioned(accountRef, groupIdHex)
                    val current = base.draft
                    if (current != null && current.content == receipt?.proposedContent) {
                        MessageDraftMutationResult.Success(current)
                    } else {
                        val merged = mergeDraftText(current?.content.orEmpty(), incoming)
                        receipt?.proposedContent = merged
                        saveDraftText(gateway, accountRef, groupIdHex, merged, base)
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // FFI gateway failures are returned; cancellation is rethrown first.
    suspend fun draftIf(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): Result<MessageDraftFfi?>? =
        withContext(ioDispatcher) {
            draftLocks.withLock(DraftKey(accountRef, groupIdHex)) {
                try {
                    val draft = gateway.read(accountRef, groupIdHex)
                    Result.success(draft).takeIf {
                        mutationGenerations.isCurrent(DraftKey(accountRef, groupIdHex), generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    Result.failure<MessageDraftFfi?>(cause).takeIf {
                        mutationGenerations.isCurrent(DraftKey(accountRef, groupIdHex), generation)
                    }
                }
            }
        }

    suspend fun deleteIf(
        accountRef: String,
        groupIdHex: String,
        generation: MessageDraftGeneration,
    ): MessageDraftConditionalDeleteResult =
        withContext(ioDispatcher) {
            draftLocks.withLock(DraftKey(accountRef, groupIdHex)) {
                if (!mutationGenerations.isCurrent(DraftKey(accountRef, groupIdHex), generation)) {
                    return@withLock MessageDraftConditionalDeleteResult.Superseded
                }
                MessageDraftConditionalDeleteResult.Applied(deleteDraft(gateway, accountRef, groupIdHex))
            }
        }
}

/** Runs one mutation, returning gateway failures as results and rethrowing cancellation. */
@Suppress("TooGenericExceptionCaught") // Repository boundary converts non-cancellation gateway failures to results.
internal suspend fun runDraftMutation(block: suspend () -> MessageDraftMutationResult): MessageDraftMutationResult =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        MessageDraftMutationResult.Failure(cause)
    }

@Suppress("TooGenericExceptionCaught")
private fun deleteDraft(
    gateway: MessageDraftGateway,
    accountRef: String,
    groupIdHex: String,
): MessageDraftMutationResult =
    try {
        gateway.deleteAgainstSelected(accountRef, groupIdHex)
        MessageDraftMutationResult.Success(draft = null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        val authoritative = authoritativeDraft(gateway, accountRef, groupIdHex)
        if (authoritative.isSuccess && authoritative.getOrNull() == null) {
            MessageDraftMutationResult.Success(draft = null)
        } else {
            MessageDraftMutationResult.Failure(cause)
        }
    }

/** Saves [content] against one read, keeping its reply and attachments, or clears an emptied draft. */
@Suppress("TooGenericExceptionCaught")
private fun saveDraftText(
    gateway: MessageDraftGateway,
    accountRef: String,
    groupIdHex: String,
    content: String,
    alreadyRead: RevisionedMessageDraft? = null,
): MessageDraftMutationResult {
    val base = alreadyRead ?: gateway.readRevisioned(accountRef, groupIdHex)
    val current = base.draft
    val deleteEmpty =
        content.isBlank() &&
            current?.replyToMessageIdHex == null &&
            current?.mediaAttachments.orEmpty().isEmpty()
    return try {
        val saved =
            if (deleteEmpty) {
                gateway.clearRevisioned(accountRef, groupIdHex, base)
                null
            } else {
                gateway.saveRevisioned(
                    accountRef,
                    groupIdHex,
                    base,
                    MessageDraftWrite(content, current?.replyToMessageIdHex, current?.mediaAttachments.orEmpty()),
                )
            }
        MessageDraftMutationResult.Success(saved)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        val authoritativeResult = authoritativeDraft(gateway, accountRef, groupIdHex)
        val authoritative = authoritativeResult.getOrNull()
        val committed =
            if (deleteEmpty) {
                authoritativeResult.isSuccess && authoritative == null
            } else {
                authoritative?.content == content
            }
        if (committed) {
            MessageDraftMutationResult.Success(authoritative)
        } else {
            MessageDraftMutationResult.Failure(cause)
        }
    }
}

/**
 * Writes an attachment change against [base] and settles its editor session against what was saved. A re-read after
 * any non-cancellation gateway failure resolves whether the write committed.
 */
@Suppress("TooGenericExceptionCaught")
private fun commitDraftAttachmentMutation(
    gateway: MessageDraftGateway,
    editorSessions: EditorSessionStore,
    accountRef: String,
    groupIdHex: String,
    base: RevisionedMessageDraft,
    updatedAttachments: List<MessageDraftAttachmentFfi>,
    changedAttachment: MessageDraftAttachmentFfi,
    pendingSession: EditorAttachmentSession?,
    previousSession: EditorAttachmentSession?,
): MessageDraftMutationResult {
    if (pendingSession != null && !editorSessions.savePending(pendingSession)) {
        return MessageDraftMutationResult.SessionPersistenceFailed
    }
    val changedDigest = changedAttachment.editorDigest()
    return try {
        val saved =
            gateway.saveRevisioned(
                accountRef,
                groupIdHex,
                base,
                MessageDraftWrite(base.draft?.content.orEmpty(), base.draft?.replyToMessageIdHex, updatedAttachments),
            )
        finishAttachmentCommit(
            editorSessions = editorSessions,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            saved = saved,
            attachmentId = changedAttachment.id,
            changedDigest = changedDigest,
            pendingSession = pendingSession,
            previousSession = previousSession,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        val reread = authoritativeDraft(gateway, accountRef, groupIdHex).getOrNull()
        val committed =
            reread?.mediaAttachments?.any {
                it.id == changedAttachment.id && it.editorDigest() == changedDigest
            } == true
        if (committed) {
            finishAttachmentCommit(
                editorSessions = editorSessions,
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                saved = requireNotNull(reread),
                attachmentId = changedAttachment.id,
                changedDigest = changedDigest,
                pendingSession = pendingSession,
                previousSession = previousSession,
            )
        } else {
            if (pendingSession != null) {
                editorSessions.discardPending(accountRef, groupIdHex, changedAttachment.id)
            }
            MessageDraftMutationResult.Failure(cause)
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private fun authoritativeDraft(
    gateway: MessageDraftGateway,
    accountRef: String,
    groupIdHex: String,
): Result<MessageDraftFfi?> =
    try {
        Result.success(gateway.read(accountRef, groupIdHex))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Exception) {
        Result.failure(cause)
    }

private fun finishAttachmentCommit(
    editorSessions: EditorSessionStore,
    accountRef: String,
    groupIdHex: String,
    saved: MessageDraftFfi,
    attachmentId: String,
    changedDigest: String,
    pendingSession: EditorAttachmentSession?,
    previousSession: EditorAttachmentSession?,
): MessageDraftMutationResult {
    val savedMatches =
        saved.mediaAttachments.any { it.id == attachmentId && it.editorDigest() == changedDigest }
    if (!savedMatches) {
        if (pendingSession != null) {
            editorSessions.discardPending(accountRef, groupIdHex, attachmentId)
        }
        return MessageDraftMutationResult.Failure(
            IllegalStateException("Saved draft did not contain the committed attachment"),
        )
    }
    val recoveryPending =
        pendingSession != null &&
            editorSessions.promote(accountRef, groupIdHex, attachmentId, changedDigest) == null
    return MessageDraftMutationResult.Success(
        draft = saved,
        previousEditorSession = previousSession,
        editorSessionRecoveryPending = recoveryPending,
    )
}
