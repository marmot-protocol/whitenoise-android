package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi

internal interface MessageDraftGateway {
    /** Reads the draft with its attachment bodies, or null when the chat has none. */
    fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi?

    /** Writes the draft unconditionally, replacing what the store holds. */
    fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi

    /** Deletes the draft unconditionally; a missing draft is not an error. */
    fun delete(
        accountRef: String,
        groupIdHex: String,
    )

    /** Lists the account's drafts without attachment bodies. */
    fun summaries(accountRef: String): List<MessageDraftSummaryFfi>

    /** The selected draft with its opaque revision, or null when this gateway cannot write revision-safely. */
    fun selected(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi? = null

    /** Writes only while the store still holds [revision]; gateways without revisions save unconditionally. */
    fun saveIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi = save(accountRef, groupIdHex, content, replyToMessageIdHex, mediaAttachments)

    /** Clears only while the store still holds [revision]; gateways without revisions delete unconditionally. */
    fun clearIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
    ) = delete(accountRef, groupIdHex)

    /** Reads the draft together with the revision it came from; gateways without revisions report none. */
    fun readRevisioned(
        accountRef: String,
        groupIdHex: String,
    ): RevisionedMessageDraft =
        RevisionedMessageDraft(
            revision = selected(accountRef, groupIdHex)?.revision,
            draft = read(accountRef, groupIdHex),
        )

    /** Writes [write] only while the store still holds [base]'s revision; gateways without one save unconditionally. */
    fun saveRevisioned(
        accountRef: String,
        groupIdHex: String,
        base: RevisionedMessageDraft,
        write: MessageDraftWrite,
    ): MessageDraftFfi {
        val (content, replyTo, attachments) = write
        return base.revision?.let { saveIfRevision(accountRef, groupIdHex, it, content, replyTo, attachments) }
            ?: save(accountRef, groupIdHex, content, replyTo, attachments)
    }

    /**
     * Copies [request]'s file into the draft as a file-backed attachment, against [base]'s revision. Only a
     * gateway backed by MarmotKit can hold files; others refuse.
     */
    suspend fun stageFile(
        accountRef: String,
        base: RevisionedMessageDraft,
        request: MessageDraftAttachmentFileRequestFfi,
        control: MediaFileTransferControlFfi,
    ): SelectedMessageDraftFfi = throw UnsupportedOperationException("this draft store cannot hold attachment files")

    /**
     * Reads up to [limit] bytes of the attachment [attachmentId] at [offset] while [revision] is selected. An
     * empty result is the end of the body; null means the draft no longer holds the attachment.
     */
    suspend fun readFileChunk(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        attachmentId: String,
        offset: Long,
        limit: Int,
    ): ByteArray? = null
}

/** The fields one draft write sets: its text, its reply target and its attachments in order. */
internal data class MessageDraftWrite(
    val content: String,
    val replyToMessageIdHex: String?,
    val mediaAttachments: List<MessageDraftAttachmentFfi>,
)

/**
 * A draft read together with the revision its contents describe. A file-backed attachment appears in [draft]
 * with its metadata and an empty body, and is listed in [fileBacked]. A write against this read keeps each of
 * those files by id, so a text save never drops or copies one.
 */
internal data class RevisionedMessageDraft(
    val revision: MessageDraftRevisionFfi?,
    val draft: MessageDraftFfi?,
    val fileBacked: Map<String, SelectedMessageDraftAttachmentFfi> = emptyMap(),
)

/**
 * Whether this attachment carries its bytes. A file-backed draft attachment read from MarmotKit carries none,
 * so its empty body must never be sent, previewed or saved as content.
 */
internal fun MessageDraftAttachmentFfi.hasInlineBody(): Boolean = plaintext.isNotEmpty()

/** Whether this read holds an attachment with [id], inline or file-backed. */
internal fun RevisionedMessageDraft.holds(id: String): Boolean = draft?.mediaAttachments?.any { it.id == id } == true

/** The draft attachment with this descriptor's metadata and [body]; a file-backed one gets an empty body. */
internal fun SelectedMessageDraftAttachmentFfi.toDraftAttachment(body: ByteArray): MessageDraftAttachmentFfi =
    MessageDraftAttachmentFfi(
        id = id,
        fileName = fileName,
        mediaType = mediaType,
        plaintext = body,
        dim = dim,
        thumbhash = thumbhash,
        durationSeconds = durationSeconds,
        waveformSamples = waveformSamples,
    )

internal class MarmotMessageDraftGateway(
    private val marmot: () -> MarmotInterface,
) : MessageDraftGateway {
    /**
     * Reads the selected draft, with file-backed attachments as body-less entries. A chat MDK no longer knows has
     * no draft, because a local removal deletes the draft with the chat, so its `UnknownGroup` is reported as
     * null. Only this plain read does that — [readRevisioned] keeps the error, so no write is based on it.
     */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? =
        try {
            readRevisioned(accountRef, groupIdHex).draft
        } catch (_: MarmotKitException.UnknownGroup) {
            null
        }

    /** Writes the draft through MDK's unconditional save. */
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

    /** Deletes the draft through MDK. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = marmot().deleteMessageDraft(accountRef, groupIdHex)

    /** Lists the account's drafts through MDK. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = marmot().messageDrafts(accountRef)

    /** Reads MDK's selected draft and revision for the group. */
    override fun selected(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi = marmot().selectedMessageDraft(accountRef, groupIdHex)

    /**
     * Revision-safe upsert; a changed selected draft surfaces as `MessageDraftRevisionConflict`. MDK returns
     * descriptors only, so the hydrated draft is rebuilt from the bytes this write submitted.
     */
    override fun saveIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        val saved =
            marmot().saveMessageDraftIfRevision(accountRef, revision, content, replyToMessageIdHex, mediaAttachments)
        val write = MessageDraftWrite(content, replyToMessageIdHex, mediaAttachments)
        return hydratedAfterWrite(groupIdHex, write, saved)
    }

    /** Revision-safe clear; a changed selected draft surfaces as `MessageDraftRevisionConflict`. */
    override fun clearIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
    ) {
        marmot().clearMessageDraftIfRevision(accountRef, revision)
    }

    /**
     * Reads the selected draft's descriptors, then each inline body under that same revision. A file-backed
     * attachment's body stays in MDK's media store; a body that vanished means the draft moved, which
     * surfaces as `MessageDraftRevisionConflict` so a mutation re-reads.
     */
    override fun readRevisioned(
        accountRef: String,
        groupIdHex: String,
    ): RevisionedMessageDraft {
        val marmot = marmot()
        val selected = marmot.selectedMessageDraft(accountRef, groupIdHex)
        val content = selected.draft ?: return RevisionedMessageDraft(selected.revision, null)
        val attachments =
            content.mediaAttachments.map { descriptor ->
                if (descriptor.fileBacked) {
                    descriptor.toDraftAttachment(ByteArray(0))
                } else {
                    val body =
                        marmot.messageDraftAttachmentIfRevision(accountRef, selected.revision, descriptor.id)
                            ?: throw MarmotKitException.MessageDraftRevisionConflict()
                    descriptor.toDraftAttachment(body)
                }
            }
        val draft =
            MessageDraftFfi(
                groupIdHex = content.groupIdHex,
                content = content.content,
                replyToMessageIdHex = content.replyToMessageIdHex,
                mediaAttachments = attachments,
                createdAtMs = content.createdAtMs,
                updatedAtMs = content.updatedAtMs,
            )
        return RevisionedMessageDraft(
            revision = selected.revision,
            draft = draft,
            fileBacked = content.mediaAttachments.filter { it.fileBacked }.associateBy { it.id },
        )
    }

    /**
     * Writes against [base]'s revision through MDK's input save. A file-backed attachment [base] holds, and any
     * attachment passed back exactly as [base] read it, is kept by id, so MDK neither copies its body again nor
     * unlinks its file. Anything else is written inline. A body-less attachment [base] does not hold as a file
     * is refused rather than saved empty. MDK keeps every attachment a save does not name, so each one [base]
     * holds that the write leaves out is named as removed.
     */
    override fun saveRevisioned(
        accountRef: String,
        groupIdHex: String,
        base: RevisionedMessageDraft,
        write: MessageDraftWrite,
    ): MessageDraftFfi {
        val revision = base.revision ?: return super.saveRevisioned(accountRef, groupIdHex, base, write)
        val held =
            base.draft
                ?.mediaAttachments
                .orEmpty()
                .associateBy { it.id }
        val inputs =
            write.mediaAttachments.map { attachment ->
                val keptFile = attachment.id in base.fileBacked && !attachment.hasInlineBody()
                check(keptFile || attachment.hasInlineBody()) { "a body-less draft attachment must be a held file" }
                if (keptFile || held[attachment.id] === attachment) {
                    MessageDraftAttachmentInputFfi.Retained(attachment.id)
                } else {
                    MessageDraftAttachmentInputFfi.Inline(attachment)
                }
            }
        val written = write.mediaAttachments.mapTo(HashSet()) { it.id }
        val removed = held.keys.filterNot { it in written }.map { MessageDraftAttachmentInputFfi.Removed(it) }
        val saved =
            marmot().saveMessageDraftInputsIfRevision(
                accountRef,
                revision,
                write.content,
                write.replyToMessageIdHex,
                inputs + removed,
            )
        return hydratedAfterWrite(groupIdHex, write, saved)
    }

    /** Copies one host file into the selected draft through MDK; the host may delete its file afterwards. */
    override suspend fun stageFile(
        accountRef: String,
        base: RevisionedMessageDraft,
        request: MessageDraftAttachmentFileRequestFfi,
        control: MediaFileTransferControlFfi,
    ): SelectedMessageDraftFfi {
        val revision = requireNotNull(base.revision) { "MDK drafts always carry a revision" }
        return marmot().stageMessageDraftAttachmentFile(accountRef, revision, request, control)
    }

    /** Reads one chunk of an inline or file-backed draft attachment through MDK. */
    override suspend fun readFileChunk(
        accountRef: String,
        revision: MessageDraftRevisionFfi,
        attachmentId: String,
        offset: Long,
        limit: Int,
    ): ByteArray? {
        val start = offset.toULong()
        return marmot().readMessageDraftAttachment(accountRef, revision, attachmentId, start, limit.toUInt())
    }
}

/** The hydrated draft a write leaves: what it submitted, with MDK's timestamps. */
private fun hydratedAfterWrite(
    groupIdHex: String,
    write: MessageDraftWrite,
    saved: SelectedMessageDraftFfi,
): MessageDraftFfi {
    val now = System.currentTimeMillis()
    return MessageDraftFfi(
        groupIdHex = groupIdHex,
        content = write.content,
        replyToMessageIdHex = write.replyToMessageIdHex,
        mediaAttachments = write.mediaAttachments,
        createdAtMs = saved.draft?.createdAtMs ?: now,
        updatedAtMs = saved.draft?.updatedAtMs ?: now,
    )
}

/**
 * The draft a stage left, built from the descriptors MarmotKit returned and the inline bodies [base] already read
 * under the lock, so a committed stage never depends on reading the draft again. A stored file gets an empty body;
 * an inline descriptor [base] did not read, which the draft's lock rules out, is left out.
 */
internal fun stagedDraft(
    base: RevisionedMessageDraft,
    staged: SelectedMessageDraftFfi,
): MessageDraftFfi? {
    val content = staged.draft ?: return null
    val bodies =
        base.draft
            ?.mediaAttachments
            .orEmpty()
            .associateBy { it.id }
    val attachments =
        content.mediaAttachments.mapNotNull { descriptor ->
            if (descriptor.fileBacked) descriptor.toDraftAttachment(ByteArray(0)) else bodies[descriptor.id]
        }
    return MessageDraftFfi(
        groupIdHex = content.groupIdHex,
        content = content.content,
        replyToMessageIdHex = content.replyToMessageIdHex,
        mediaAttachments = attachments,
        createdAtMs = content.createdAtMs,
        updatedAtMs = content.updatedAtMs,
    )
}
