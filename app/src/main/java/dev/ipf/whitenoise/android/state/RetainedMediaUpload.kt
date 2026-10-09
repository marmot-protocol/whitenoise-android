package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SendSummaryFfi

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

    /** Android intake resources share the queue's existing removal/discard/eviction lifetime. */
    fun retainSource(release: () -> Unit): Boolean {
        val available = sourceRelease == null
        if (available) sourceRelease = release
        return available
    }

    /** Clears ownership before invoking cleanup, making removal and delayed acceptance releases idempotent. */
    fun releaseSource() {
        val release = sourceRelease
        sourceRelease = null
        release?.invoke()
    }
}
