package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * File-backed draft attachments: a pick too large for memory is copied into the draft as a file MarmotKit
 * stores, and its body is read back in chunks. Shares [MessageDraftRepository]'s per-draft locks and mutation
 * generations, so a copy and every other draft write are ordered.
 */
internal class MessageDraftFiles(
    private val gateway: MessageDraftGateway,
    private val draftLocks: MessageDraftLocks,
    private val mutationGenerations: MessageDraftMutationGenerations,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Reads the draft with its revision and its file-backed attachments; failures are returned, not thrown. */
    @Suppress("TooGenericExceptionCaught") // FFI gateway failures are returned; cancellation is rethrown first.
    suspend fun revisioned(
        accountRef: String,
        groupIdHex: String,
    ): Result<RevisionedMessageDraft> =
        withContext(ioDispatcher) {
            try {
                Result.success(gateway.readRevisioned(accountRef, groupIdHex))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                Result.failure(cause)
            }
        }

    /**
     * Copies [request]'s file into the draft as a file-backed attachment. The draft's lock is held for the whole
     * copy, so no save can write the revision the copy replaces. Unlike other mutations a lost revision is not
     * retried: MDK discards a copy whose draft was cleared or sent meanwhile, and copying it into the next draft
     * would bring back an attachment that already left. [control] reports progress and cancels the copy. Once
     * MarmotKit has stored the file the mutation is accepted, and its result comes from what the stage returned,
     * so no later read can report a stored file as failed.
     */
    suspend fun add(
        accountRef: String,
        groupIdHex: String,
        request: MessageDraftAttachmentFileRequestFfi,
        control: MediaFileTransferControlFfi,
    ): MessageDraftMutationResult {
        val key = DraftKey(accountRef, groupIdHex)
        return withContext(ioDispatcher) {
            draftLocks.withLock(key) {
                runDraftMutation {
                    val base = gateway.readRevisioned(accountRef, groupIdHex)
                    if (base.holds(request.id)) {
                        return@runDraftMutation MessageDraftMutationResult.DuplicateAttachment
                    }
                    val staged = gateway.stageFile(accountRef, base, request, control)
                    mutationGenerations.advance(key)
                    MessageDraftMutationResult.Success(stagedDraft(base, staged))
                }
            }
        }
    }

    /** Reads up to [limit] bytes of a draft attachment's body at [offset] while [revision] is selected. */
    suspend fun readChunk(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        attachmentId: String,
        offset: Long,
        limit: Int,
    ): ByteArray? =
        withContext(ioDispatcher) {
            gateway.readFileChunk(accountRef, revision, attachmentId, offset, limit)
        }
}
