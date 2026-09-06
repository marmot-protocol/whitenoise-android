package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface MessageDraftGateway {
    fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi?

    fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi

    fun delete(
        accountRef: String,
        groupIdHex: String,
    )

    fun summaries(accountRef: String): List<MessageDraftSummaryFfi>
}

internal class MarmotMessageDraftGateway(
    private val marmot: () -> MarmotInterface,
) : MessageDraftGateway {
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = marmot().messageDraft(accountRef, groupIdHex)

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        marmot().saveMessageDraft(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            content = content,
            replyToMessageIdHex = replyToMessageIdHex,
            mediaAttachments = mediaAttachments,
        )

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = marmot().deleteMessageDraft(accountRef, groupIdHex)

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = marmot().messageDrafts(accountRef)
}

internal sealed interface MessageDraftMutationResult {
    data class Success(
        val draft: MessageDraftFfi?,
        val previousEditorSession: EditorAttachmentSession? = null,
        val editorSessionRecoveryPending: Boolean = false,
    ) : MessageDraftMutationResult

    data object MissingAttachment : MessageDraftMutationResult

    data object StaleAttachment : MessageDraftMutationResult

    data object DuplicateAttachment : MessageDraftMutationResult

    data object InvalidEditorSession : MessageDraftMutationResult

    data object SessionPersistenceFailed : MessageDraftMutationResult

    data class Failure(
        val cause: Throwable,
    ) : MessageDraftMutationResult
}

internal sealed interface MessageDraftConditionalDeleteResult {
    data class Applied(
        val result: MessageDraftMutationResult,
    ) : MessageDraftConditionalDeleteResult

    data object Superseded : MessageDraftConditionalDeleteResult
}

internal data class MessageDraftGeneration(
    val value: Long,
)

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

    suspend fun mergeText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            val trimmedIncoming = incoming.trim()
            if (trimmedIncoming.isEmpty()) {
                return@mutate MessageDraftMutationResult.Success(gateway.read(accountRef, groupIdHex))
            }
            val current = gateway.read(accountRef, groupIdHex)
            val merged =
                if (current?.content.isNullOrBlank()) {
                    trimmedIncoming
                } else {
                    "${current.content.trimEnd()}\n$trimmedIncoming"
                }
            saveDraftText(gateway, accountRef, groupIdHex, merged, current)
        }

    suspend fun addAttachment(
        accountRef: String,
        groupIdHex: String,
        attachment: MessageDraftAttachmentFfi,
        pendingSession: EditorAttachmentSession? = null,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            val current = gateway.read(accountRef, groupIdHex)
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
                before = current,
                updatedAttachments = current?.mediaAttachments.orEmpty() + attachment,
                changedAttachment = attachment,
                pendingSession = pendingSession,
                previousSession = null,
            )
        }

    suspend fun replaceAttachment(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        expectedDigest: String,
        replacement: MessageDraftAttachmentFfi,
        pendingSession: EditorAttachmentSession,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            if (replacement.id != attachmentId ||
                !pendingSession.matches(accountRef, groupIdHex, replacement)
            ) {
                return@mutate MessageDraftMutationResult.InvalidEditorSession
            }
            val current = gateway.read(accountRef, groupIdHex)
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
                before = current,
                updatedAttachments = updated,
                changedAttachment = replacement,
                pendingSession = pendingSession,
                previousSession = previousSession,
            )
        }

    @Suppress("TooGenericExceptionCaught") // FFI gateways can surface unchecked failures; cancellation is rethrown.
    suspend fun removeAttachment(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
        expectedDigest: String? = null,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            val current = gateway.read(accountRef, groupIdHex)
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
                        gateway.delete(accountRef, groupIdHex)
                        null
                    } else {
                        gateway.save(
                            accountRef = accountRef,
                            groupIdHex = groupIdHex,
                            content = current.content,
                            replyToMessageIdHex = current.replyToMessageIdHex,
                            mediaAttachments = updated,
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

    private suspend fun mutate(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> MessageDraftMutationResult,
    ): MessageDraftMutationResult {
        val key = DraftKey(accountRef, groupIdHex)
        return withContext(ioDispatcher) {
            draftLocks.withLock(key) {
                val result = runDraftMutation(block)
                if (result is MessageDraftMutationResult.Success) mutationGenerations.advance(key)
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

    suspend fun saveAcceptedText(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ): MessageDraftMutationResult =
        withContext(ioDispatcher) {
            draftLocks.withLock(DraftKey(accountRef, groupIdHex)) {
                runDraftMutation { saveDraftText(gateway, accountRef, groupIdHex, content) }
            }
        }

    suspend fun mergeAcceptedText(
        accountRef: String,
        groupIdHex: String,
        incoming: String,
    ): MessageDraftMutationResult =
        withContext(ioDispatcher) {
            draftLocks.withLock(DraftKey(accountRef, groupIdHex)) {
                runDraftMutation {
                    val current = gateway.read(accountRef, groupIdHex)
                    val merged = mergeDraftText(current?.content.orEmpty(), incoming)
                    saveDraftText(gateway, accountRef, groupIdHex, merged, current)
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

internal class MessageDraftMutationGenerations {
    private val lock = Any()
    private val lifetimes = mutableMapOf<DraftKey, StalenessGuard>()

    /** Accepts a new mutation for [key] and returns its guarded generation. */
    fun advance(key: DraftKey): MessageDraftGeneration =
        synchronized(lock) {
            MessageDraftGeneration(lifetime(key).advance())
        }

    /** Atomically accepts a mutation only while [expected] still owns [key]. */
    fun advanceIfCurrent(
        key: DraftKey,
        expected: MessageDraftGeneration,
    ): MessageDraftGeneration? =
        synchronized(lock) {
            lifetime(key).advanceIfCurrent(expected.value)?.let(::MessageDraftGeneration)
        }

    /** Captures the mutation generation currently visible for [key]. */
    fun current(key: DraftKey) = synchronized(lock) { MessageDraftGeneration(lifetime(key).capture()) }

    /** Reports whether [generation] remains the newest accepted mutation for [key]. */
    fun isCurrent(
        key: DraftKey,
        generation: MessageDraftGeneration,
    ): Boolean = synchronized(lock) { lifetime(key).isCurrent(generation.value) }

    /** Returns the single staleness primitive assigned to one draft key. */
    private fun lifetime(key: DraftKey): StalenessGuard = lifetimes.getOrPut(key, ::StalenessGuard)
}

@Suppress("TooGenericExceptionCaught") // Repository boundary converts non-cancellation gateway failures to results.
private suspend fun runDraftMutation(block: suspend () -> MessageDraftMutationResult): MessageDraftMutationResult =
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
        gateway.delete(accountRef, groupIdHex)
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

@Suppress("TooGenericExceptionCaught")
private fun saveDraftText(
    gateway: MessageDraftGateway,
    accountRef: String,
    groupIdHex: String,
    content: String,
    alreadyRead: MessageDraftFfi? = null,
): MessageDraftMutationResult {
    val current = alreadyRead ?: gateway.read(accountRef, groupIdHex)
    val deleteEmpty =
        content.isBlank() &&
            current?.replyToMessageIdHex == null &&
            current?.mediaAttachments.orEmpty().isEmpty()
    return try {
        val saved =
            if (deleteEmpty) {
                gateway.delete(accountRef, groupIdHex)
                null
            } else {
                gateway.save(
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                    content = content,
                    replyToMessageIdHex = current?.replyToMessageIdHex,
                    mediaAttachments = current?.mediaAttachments.orEmpty(),
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

// Re-read after any non-cancellation gateway failure resolves commit ambiguity.
@Suppress("TooGenericExceptionCaught")
private fun commitDraftAttachmentMutation(
    gateway: MessageDraftGateway,
    editorSessions: EditorSessionStore,
    accountRef: String,
    groupIdHex: String,
    before: MessageDraftFfi?,
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
            gateway.save(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                content = before?.content.orEmpty(),
                replyToMessageIdHex = before?.replyToMessageIdHex,
                mediaAttachments = updatedAttachments,
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

internal data class DraftKey(
    val accountRef: String,
    val groupIdHex: String,
)

internal class MessageDraftLocks {
    private val tableGuard = Any()
    private val table = mutableMapOf<DraftKey, DraftLock>()

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

private fun editorSessionStoreUnavailable() = IllegalStateException(EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE)

private fun editorSourceStoreUnavailable() = IllegalStateException(EDITOR_SOURCE_STORE_UNAVAILABLE_MESSAGE)

private const val EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE =
    "Encrypted editor sessions are temporarily unavailable"

private const val EDITOR_SOURCE_STORE_UNAVAILABLE_MESSAGE =
    "Encrypted editor sources are temporarily unavailable"

internal fun MessageDraftAttachmentFfi.editorDigest(): String =
    editorAttachmentDigest(
        attachmentId = id,
        fileName = fileName,
        mediaType = mediaType,
        plaintext = plaintext,
        dim = dim,
        thumbhash = thumbhash,
        durationSeconds = durationSeconds,
        waveformSamples = waveformSamples,
    )

private fun EditorAttachmentSession.matches(
    accountRef: String,
    groupIdHex: String,
    attachment: MessageDraftAttachmentFfi,
): Boolean =
    phase == EditorSessionPhase.Pending &&
        this.accountRef == accountRef &&
        this.groupIdHex == groupIdHex &&
        attachmentId == attachment.id &&
        attachmentDigest == attachment.editorDigest()
