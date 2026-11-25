package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Compressed bytes + metadata retained for an in-flight/failed media send.
 * The whole album is one unit: all attachments succeed/fail together, retry
 * re-runs the whole upload, discard drops them all. `uploadedReferences`
 * caches the per-attachment Blossom result so a publish-only failure retries
 * the publish without re-uploading every blob.
 */
internal class RetainedMediaUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
    val replyTarget: String? = null,
    val replyRevision: MessageDraftRevisionFfi? = null,
    val replyVersion: Long = 0L,
) {
    var uploadedReferences: List<MediaAttachmentReferenceFfi>? = null

    // The draft a send of stored draft files may consume on admission: the one it uploaded from, while that draft
    // said exactly what it sends.
    var draftUploadRevision: MessageDraftRevisionFfi? = null
    var draftSendClearToken: DraftSendClearToken? = null

    // Private copies of this send's stored draft files, by draft id, taken once an upload from the draft failed.
    private var draftCopies: Map<String, StagedUploadSource> = emptyMap()
    private var stagedSourcesReleased = false
    var localAcceptance: SendSummaryFfi? = null
    var recoveredWithoutUpload: Boolean = false
    var acceptedPending: Boolean = false
    var acceptedPendingMessageIdHex: String? = null
    private var sourceRelease: (() -> Unit)? = null
    private var transferCancel: (() -> Unit)? = null
    private var transferCancelRequested = false
    private val transferProgress = MutableStateFlow<FileUploadProgress?>(null)

    // MDK keeps one maximum across a send's items, so its counter describes a single file. An album or a send
    // held in memory shows none.
    private val singleFileBytes: Long? =
        attachments
            .singleOrNull()
            ?.takeIf { it.isFileBacked }
            ?.byteCount
            ?.takeIf { it > 0L }

    /** The running single-file transfer's progress, or null while none is known or after it failed. */
    val uploadProgress: StateFlow<FileUploadProgress?> = transferProgress.asStateFlow()

    /** Publishes MDK's transfer counter for this send, keeping the last value once every byte is sent. */
    fun reportTransferProgress(processed: Long) {
        val fileBytes = singleFileBytes ?: return
        val next = fileUploadProgress(processed, fileBytes) ?: return
        transferProgress.update { current ->
            if (current != null && current.fraction > next.fraction) current else next
        }
    }

    /** Forgets the progress of an attempt that ended without a send, so a failed bubble shows no stale bytes. */
    fun clearTransferProgress() {
        transferProgress.value = null
    }

    /**
     * Registers how to stop the file-backed transfer now running for this send; null once it has returned.
     * A Cancel recorded while the send was still waiting for the commit lock stops the transfer as it registers.
     */
    fun attachTransferCancel(cancel: (() -> Unit)?) {
        transferCancel = cancel
        if (cancel != null && transferCancelRequested) cancel()
    }

    /**
     * Records the user's Cancel for this send and stops a running file-backed transfer now, without waiting
     * for the group commit lock it holds. A transfer that has not started yet is stopped as it registers.
     */
    fun requestTransferCancel() {
        transferCancelRequested = true
        transferCancel?.invoke()
    }

    /** Forgets the uploaded references when MDK refused them for a stale epoch (mdk#2891), so a retry uploads again. */
    fun forgetUploadAfter(failure: Throwable) {
        if (isStaleMediaReferenceEpoch(failure)) uploadedReferences = null
    }

    /** Whether the user's Cancel is waiting to be settled, without consuming it. */
    val isTransferCancelRequested: Boolean
        get() = transferCancelRequested

    /** Whether this send owns a private copy of every stored draft file it carries, so retries need no draft. */
    val ownsDraftCopies: Boolean
        get() = isDraftStored && attachments.all { it.draftFile == null || it.draftFile.attachmentId in draftCopies }

    /**
     * Takes ownership of [copies], private copies of this send's stored draft files by draft id, released with the
     * send's other staged files. Copies arriving after those were released, or while it already owns some, are closed.
     */
    fun adoptDraftCopies(
        copies: Map<String, StagedUploadSource>,
        closeStaged: (StagedUploadSource) -> Unit = StagedUploadSource::closeQuietly,
    ) {
        if (stagedSourcesReleased || draftCopies.isNotEmpty()) {
            copies.values.forEach(closeStaged)
        } else {
            draftCopies = copies
        }
    }

    /** The send's attachments, each stored draft file replaced by the private copy this send owns of it. */
    fun ownedUploadAttachments(): List<PendingAttachment> =
        attachments.map { attachment ->
            val copy = attachment.draftFile?.let { draftCopies[it.attachmentId] }
            if (copy == null) attachment else attachment.copy(sourceFile = copy, draftFile = null)
        }

    /** Whether the user cancelled this send; reading it clears the request. */
    fun consumeTransferCancelRequest(): Boolean {
        val requested = transferCancelRequested
        transferCancelRequested = false
        return requested
    }

    /**
     * Hands every staged snapshot to [closeStaged]. Once the upload has produced its references, a
     * publish-only retry never reads them again, so they need not wait for the whole send to settle.
     */
    fun releaseStagedSources(closeStaged: (StagedUploadSource) -> Unit) {
        stagedSourcesReleased = true
        attachments.forEach { attachment -> attachment.sourceFile?.let(closeStaged) }
        val copies = draftCopies.values
        draftCopies = emptyMap()
        copies.forEach(closeStaged)
    }

    /** Android intake resources share the queue's existing removal/discard/eviction lifetime. */
    fun retainSource(release: () -> Unit): Boolean {
        val available = sourceRelease == null
        if (available) sourceRelease = release
        return available
    }

    /**
     * Clears ownership before invoking cleanup, making removal and delayed acceptance releases idempotent.
     * Private staged files of file-backed attachments end here too, through [closeStaged] so callers on
     * the main thread can delete them elsewhere; a native snapshot still reading one keeps it until its
     * own lease closes.
     */
    fun releaseSource(closeStaged: (StagedUploadSource) -> Unit = StagedUploadSource::closeQuietly) {
        val release = sourceRelease
        sourceRelease = null
        try {
            release?.invoke()
        } finally {
            releaseStagedSources(closeStaged)
        }
    }
}
