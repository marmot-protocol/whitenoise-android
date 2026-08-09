package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
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
}

internal class MarmotMessageDraftGateway(
    private val marmot: () -> Marmot,
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
    private val lockTableGuard = Any()
    private val lockTable = mutableMapOf<DraftKey, DraftLock>()

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

    suspend fun saveText(
        accountRef: String,
        groupIdHex: String,
        content: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            val current = gateway.read(accountRef, groupIdHex)
            MessageDraftMutationResult.Success(
                gateway.save(
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                    content = content,
                    replyToMessageIdHex = current?.replyToMessageIdHex,
                    mediaAttachments = current?.mediaAttachments.orEmpty(),
                ),
            )
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
            commitAttachmentMutation(
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
            commitAttachmentMutation(
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
                val reread = runCatching { gateway.read(accountRef, groupIdHex) }.getOrNull()
                val committed =
                    if (deleteEmptyDraft) {
                        reread == null
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

    suspend fun delete(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult =
        mutate(accountRef, groupIdHex) {
            gateway.delete(accountRef, groupIdHex)
            MessageDraftMutationResult.Success(draft = null)
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
                Result.success(sources.reconcile(sourceLeaseReferences))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }

    // Re-read after any non-cancellation gateway failure resolves commit ambiguity.
    @Suppress("TooGenericExceptionCaught")
    private fun commitAttachmentMutation(
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
            val reread = runCatching { gateway.read(accountRef, groupIdHex) }.getOrNull()
            val committed =
                reread?.mediaAttachments?.any {
                    it.id == changedAttachment.id && it.editorDigest() == changedDigest
                } == true
            if (committed) {
                finishAttachmentCommit(
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

    private fun finishAttachmentCommit(
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

    @Suppress("TooGenericExceptionCaught") // Repository boundary converts non-cancellation gateway failures to results.
    private suspend fun mutate(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> MessageDraftMutationResult,
    ): MessageDraftMutationResult =
        withContext(ioDispatcher) {
            val key = DraftKey(accountRef, groupIdHex)
            withDraftLock(key) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    MessageDraftMutationResult.Failure(cause)
                }
            }
        }

    private suspend fun <T> withDraftLock(
        key: DraftKey,
        block: suspend () -> T,
    ): T {
        val entry =
            synchronized(lockTableGuard) {
                lockTable.getOrPut(key) { DraftLock() }.also { it.users += 1 }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(lockTableGuard) {
                entry.users -= 1
                if (entry.users == 0 && lockTable[key] === entry) lockTable.remove(key)
            }
        }
    }

    private data class DraftKey(
        val accountRef: String,
        val groupIdHex: String,
    )

    private class DraftLock(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}

private fun editorSessionStoreUnavailable() = IllegalStateException(EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE)

private const val EDITOR_SESSION_STORE_UNAVAILABLE_MESSAGE =
    "Encrypted editor sessions are temporarily unavailable"

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
