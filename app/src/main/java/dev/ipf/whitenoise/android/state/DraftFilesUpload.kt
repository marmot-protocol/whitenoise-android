@file:Suppress("TooManyFunctions") // Stored-file uploads and collections share the same selected-draft CAS.

package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
import java.io.File

/** How many times consuming a sent send's leftovers reads a draft again after another save moved it. */
private const val MAX_SENT_DRAFT_CONSUME_ATTEMPTS = 3

/** How many times a stored-file upload reads the draft before a draft that keeps moving fails the send. */
private const val MAX_DRAFT_UPLOAD_READS = 3

/** A send whose attachments are files the composer's draft stores, and the caption it carries. */
internal data class DraftFilesUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
)

/**
 * Uploads a send of stored draft files from the copies the draft holds, by id, so nothing is read from the pick again.
 * The draft must still hold each of them as a stored file of the same size, name and type, or the send fails before
 * anything uploads; anything newer the draft holds, text typed or attachments added since, is left alone and nothing
 * is saved. The returned revision is the draft the send may consume on admission, and only when it held exactly this
 * send: these files, in order, under the send's caption and with no reply. A save that moves the draft while its
 * files are checked, such as the next message being typed, is followed: the draft is read and checked again.
 */
internal suspend fun MarmotInterface.uploadComposerDraftFilesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    control: MediaFileTransferControlFfi,
    token: String,
    verificationDirectory: File,
): DurableComposerMediaUpload {
    recoveredDraftFilesSend(account, group, upload, token)?.let { return it }
    var moved: MarmotKitException.MessageDraftRevisionConflict? = null
    repeat(MAX_DRAFT_UPLOAD_READS) {
        try {
            return uploadSelectedDraftFiles(account, group, upload, control, verificationDirectory)
        } catch (conflict: MarmotKitException.MessageDraftRevisionConflict) {
            // MDK checks the revision while the files are read and as the upload opens them, before any byte is
            // sent, so reading the draft again repeats no upload.
            moved = conflict
        }
    }
    throw checkNotNull(moved)
}

/** One attempt of [uploadComposerDraftFilesWithToken] against the draft as it is selected now. */
private suspend fun MarmotInterface.uploadSelectedDraftFiles(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    control: MediaFileTransferControlFfi,
    verificationDirectory: File,
): DurableComposerMediaUpload {
    val selected = selectedMessageDraft(account, group)
    val draft = selected.draft
    val ids =
        checkNotNull(draft?.let { heldDraftFileIds(it, upload.attachments) }) {
            "the draft no longer holds this send's stored files"
        }
    verifySelectedDraftPdfMetadata(account, selected.revision, upload.attachments, verificationDirectory)
    return DurableComposerMediaUpload(
        upload = uploadMessageDraftAttachments(account, selected.revision, ids, null, control),
        acceptance = null,
        recoveredWithoutUpload = false,
        draftRevision = selected.revision.takeIf { draftIsExactly(draft, upload) },
    )
}

/**
 * Uploads the private copies a failed send of stored draft files took of them, through [request], so a retry needs
 * nothing the draft still holds. Admission stays with [sendComposerDraftFiles], as for an upload from the draft.
 */
internal suspend fun MarmotInterface.uploadOwnedDraftCopiesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    request: MediaFileUploadRequestFfi,
    control: MediaFileTransferControlFfi,
    token: String,
): DurableComposerMediaUpload {
    require(!request.send) { "owned draft copies upload before admission" }
    recoveredDraftFilesSend(account, group, upload, token)?.let { return it }
    verifyCopiedDraftPdfMetadata(request)
    return DurableComposerMediaUpload(
        upload = uploadMediaFiles(account, group, request, control),
        acceptance = null,
        recoveredWithoutUpload = false,
    )
}

/** A send MDK already owns under [token], with what it left in the draft consumed, or null when there is none. */
private fun MarmotInterface.recoveredDraftFilesSend(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    token: String,
): DurableComposerMediaUpload? =
    recoveredLocalSend(account, group, token)?.let { recovered ->
        consumeSentDraftFiles(account, group, upload)
        recoveredComposerUpload(recovered)
    }

/**
 * Publishes a retained send. A stored-file send can consume its unchanged, fully matching upload revision.
 * An exact inline selection can consume its matching revision only while it still owns the caption.
 * Partial composer selections and collection chunks admit independently of the native draft.
 * Captured replies and callers without explicit composer ownership keep their existing admission paths.
 */
internal suspend fun MarmotInterface.publishRetainedMedia(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    admission: DraftFilesAdmission,
): SendSummaryFfi =
    when {
        retained.isDraftStored && retained.draftHandling != RetainedDraftHandling.COLLECTION_CHUNK -> {
            val upload = DraftFilesUpload(retained.attachments, retained.caption)
            sendComposerDraftFiles(account, group, references, upload, admission)
        }
        retained.admitsPreparedMedia ->
            admitLocalSend(account, group, admission.token) {
                val consuming =
                    if (retained.draftHandling == RetainedDraftHandling.COMPOSER_SELECTION && admission.ownsCaption()) {
                        exactInlineDraftRevision(account, group, retained).takeIf { admission.ownsCaption() }
                    } else {
                        null
                    }
                sendPreparedMediaWithClientToken(
                    account,
                    group,
                    references,
                    retained.caption,
                    admission.token,
                    consumingDraft = consuming,
                )
            }
        else ->
            sendComposerMedia(
                account,
                group,
                references,
                retained.caption,
                admission.token,
                replyRevision = retained.replyRevision,
            )
    }

/**
 * Captures a revision only when every staged inline body is the exact native row with the captured caption
 * and there are no other rows or reply. Revision-bound reads prevent a concurrent edit from authorizing removal.
 */
@Suppress("SwallowedException") // If an exact match cannot be proved, admission preserves the draft revision.
private fun MarmotInterface.exactInlineDraftRevision(
    account: String,
    group: String,
    retained: RetainedMediaUpload,
): MessageDraftRevisionFfi? {
    val picks = retained.attachments
    // Bytes that send preparation cleaned or moved to a file are not the draft's: MDK refuses to consume a current
    // draft whose bodies differ from the uploads, so those sends leave their originals to exact-ID cleanup.
    val isInlineSelection =
        picks.all { !it.isFileBacked && it.nativeDraftAttachmentId != null && it.nativeDraftBody == null }
    val selected = if (isInlineSelection) selectedDraftOrNull(account, group) else null
    val draft = selected?.draft ?: return null
    val matchingCaption = draft.content.trim() == retained.caption.orEmpty().trim()
    val matchingShape =
        draft.replyToMessageIdHex == null && matchingCaption && draft.mediaAttachments.size == picks.size
    return try {
        selected.revision.takeIf {
            matchingShape &&
                draft.mediaAttachments.zip(picks).all { (stored, pick) ->
                    val sameAttachment =
                        !stored.fileBacked &&
                            stored.id == pick.nativeDraftAttachmentId &&
                            stored.fileName == pick.fileName &&
                            stored.mediaType == pick.mediaType
                    val body = if (sameAttachment) messageDraftAttachmentIfRevision(account, it, stored.id) else null
                    body?.contentEquals(pick.plaintextBytes) == true
                }
        }
    } catch (conflict: MarmotKitException) {
        null
    }
}

/** The token, selected revision and text ownership of an admitted send. */
internal data class DraftFilesAdmission(
    val consuming: MessageDraftRevisionFfi?,
    val token: String,
    val ownsCaption: () -> Boolean = { false },
)

/**
 * Admits uploaded stored files as the send's own snapshot, its caption and references, under its token. MarmotKit
 * consumes the draft [DraftFilesAdmission.consuming] names only while it is unchanged; a draft saved since, such as
 * text typed during a slow upload, is never overwritten or consumed. What the send left in such a draft is then
 * removed by [consumeSentDraftFiles].
 */
internal suspend fun MarmotInterface.sendComposerDraftFiles(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    upload: DraftFilesUpload,
    admission: DraftFilesAdmission,
): SendSummaryFfi {
    val summary =
        admitLocalSend(account, group, admission.token) {
            sendPreparedMediaWithClientToken(
                account,
                group,
                references,
                upload.caption,
                admission.token,
                admission.consuming,
            )
        }
    consumeSentDraftFiles(account, group, upload, admission.ownsCaption)
    return summary
}

/**
 * Removes what an admitted send of stored files left in the selected draft: each of its files the draft still holds,
 * and its matching caption while the send still owns the composer text. A later edit can contain identical text.
 * Newer text, the reply and attachments added since stay. A draft left empty is cleared. Cleanup is best effort.
 */
internal fun MarmotInterface.consumeSentDraftFiles(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    ownsCaption: () -> Boolean = { false },
) {
    consumeSelectedDraftFiles(
        account,
        group,
        matches = { stored, _ -> sentFileOf(stored, upload.attachments) },
        consumeText = { ownsCaption() && it.content.trim() == upload.caption.orEmpty().trim() },
    )
}

/**
 * Removes what an accepted, journaled send left in the selected draft, in one revision-checked save: the original
 * attachment IDs in [accepted], and the caption [caption] was captured from while the draft is still that exact save.
 * A draft saved since keeps its text, whatever it says. [onCaptionCleared] runs once the caption is gone.
 */
internal fun MarmotInterface.consumeCollectionDraftFiles(
    operation: AttachmentCollectionOperation,
    accepted: List<CollectionStoredDraft>,
    caption: CollectionSentCaption? = null,
    onCaptionCleared: () -> Unit = {},
): Boolean {
    val sources = accepted.associateBy { it.attachmentId }
    val cleanup =
        consumeSelectedDraftFiles(
            operation.accountRef,
            operation.groupIdHex,
            matches = { draft, revision ->
                val source = sources[draft.id]
                val sameDescriptor =
                    source != null &&
                        source.fileBacked == draft.fileBacked &&
                        source.byteCount == draft.plaintextSize.toLong()
                val account = operation.accountRef
                sameDescriptor && (draft.fileBacked || holdsInlineBody(account, revision, draft.id, source))
            },
            consumeText = { draft -> caption != null && draft.isSaveOf(caption) },
        )
    if (cleanup?.clearedText == true) onCaptionCleared()
    return cleanup != null
}

/** Whether the selected draft is still the save [caption] was read from, so its text is the accepted caption. */
private fun SelectedMessageDraftContentFfi.isSaveOf(caption: CollectionSentCaption): Boolean =
    createdAtMs == caption.draftCreatedAtMs && updatedAtMs == caption.draftUpdatedAtMs

/** Whether the draft still holds, under [id] at [revision], the inline body [source] was captured from. */
private fun MarmotInterface.holdsInlineBody(
    account: String,
    revision: MessageDraftRevisionFfi,
    id: String,
    source: CollectionStoredDraft?,
): Boolean {
    val expected = source?.inlineSha256 ?: return false
    return messageDraftAttachmentIfRevision(account, revision, id)?.let { sha256(it).toHex() == expected } == true
}

/** What a finished cleanup of the selected draft did to its text. */
private data class SentDraftCleanup(
    val clearedText: Boolean,
)

/**
 * Revision-checks removal of matched files and claimed text, preserving the reply and everything unmatched. Returns
 * null when the draft could not be read or written, so callers that can retry after admission do.
 */
@Suppress("ReturnCount", "SwallowedException") // Each failure is reported as null; a lost race reads again.
private fun MarmotInterface.consumeSelectedDraftFiles(
    account: String,
    group: String,
    matches: (SelectedMessageDraftAttachmentFfi, MessageDraftRevisionFfi) -> Boolean,
    consumeText: (SelectedMessageDraftContentFfi) -> Boolean = { false },
): SentDraftCleanup? {
    repeat(MAX_SENT_DRAFT_CONSUME_ATTEMPTS) {
        val selected = selectedDraftOrNull(account, group) ?: return null
        val draft = selected.draft ?: return SentDraftCleanup(clearedText = false)
        try {
            val sent = draft.mediaAttachments.filter { matches(it, selected.revision) }.mapTo(HashSet()) { it.id }
            val clearsText = draft.content.isNotBlank() && consumeText(draft)
            if (sent.isEmpty() && !clearsText) return SentDraftCleanup(clearedText = false)
            val kept = draft.mediaAttachments.filterNot { it.id in sent }
            val text = if (clearsText) "" else draft.content
            if (kept.isEmpty() && text.isBlank() && draft.replyToMessageIdHex == null) {
                clearMessageDraftIfRevision(account, selected.revision)
            } else {
                val inputs =
                    kept.map { MessageDraftAttachmentInputFfi.Retained(it.id) } +
                        sent.map { MessageDraftAttachmentInputFfi.Removed(it) }
                saveMessageDraftInputsIfRevision(account, selected.revision, text, draft.replyToMessageIdHex, inputs)
            }
            return SentDraftCleanup(clearedText = clearsText)
        } catch (moved: MarmotKitException.MessageDraftRevisionConflict) {
            // Another save won the race: read the draft it left and try again.
        } catch (refused: MarmotKitException) {
            return null
        }
    }
    return null
}

/** Whether [descriptor] is one of [attachments]' stored files: the same id, still stored, at the same size. */
private fun sentFileOf(
    descriptor: SelectedMessageDraftAttachmentFfi,
    attachments: List<PendingAttachment>,
): Boolean =
    attachments.any { attachment ->
        val stored = attachment.draftFile
        stored != null && isStoredFile(descriptor, stored)
    }

/** Whether [descriptor] is [stored]: the same id, still a stored file, of the same size. */
internal fun isStoredFile(
    descriptor: SelectedMessageDraftAttachmentFfi,
    stored: DraftStoredFile,
): Boolean =
    descriptor.fileBacked &&
        stored.attachmentId == descriptor.id &&
        stored.byteCount == descriptor.plaintextSize.toLong()

/**
 * The draft ids of [attachments] when the draft holds each of them as a stored file of the same size, name and type,
 * whatever else it holds; otherwise null. An inline attachment under a stored file's id never matches.
 */
internal fun heldDraftFileIds(
    draft: SelectedMessageDraftContentFfi,
    attachments: List<PendingAttachment>,
): List<String>? {
    val ids =
        attachments.mapNotNull { attachment ->
            val stored = attachment.draftFile
            draft.mediaAttachments
                .firstOrNull { descriptor ->
                    stored != null &&
                        isStoredFile(descriptor, stored) &&
                        descriptor.fileName == attachment.fileName &&
                        descriptor.mediaType == attachment.mediaType
                }?.id
        }
    return ids.takeIf { it.size == attachments.size }
}

/**
 * Whether [draft] says exactly what [upload] sends, so MarmotKit may consume it on admission: these stored files and
 * nothing else, in order, under the caption, with no reply.
 */
internal fun draftIsExactly(
    draft: SelectedMessageDraftContentFfi?,
    upload: DraftFilesUpload,
): Boolean =
    draft != null &&
        draft.replyToMessageIdHex == null &&
        draft.content.trim() == upload.caption.orEmpty().trim() &&
        heldDraftFileIds(draft, upload.attachments) == draft.mediaAttachments.map { it.id }
