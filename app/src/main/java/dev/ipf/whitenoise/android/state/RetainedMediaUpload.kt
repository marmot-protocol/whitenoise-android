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
    var localAcceptance: SendSummaryFfi? = null
    var recoveredWithoutUpload: Boolean = false
    var acceptedPending: Boolean = false
    var acceptedPendingMessageIdHex: String? = null
    private var sourceRelease: (() -> Unit)? = null
    private var transferCancel: (() -> Unit)? = null
    private var transferCancelRequested = false
    private val transferProgress = MutableStateFlow<FileUploadProgress?>(null)

    // MDK's transfer counter describes a send of one file only, so an album or an in-memory send shows none.
    private val singleFileBytes: Long? =
        attachments
            .singleOrNull()
            ?.takeIf { it.sourceFile != null }
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
        attachments.forEach { attachment -> attachment.sourceFile?.let(closeStaged) }
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
