package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi

/**
 * Whether MDK holds [request]'s body parked for lack of storage, read once and without demanding anything. A park only
 * follows a demand, so a surface entered after one can restore the reader's earlier download instead of offering
 * Download again. Only a request whose native target is already known is read, so no history paging runs.
 */
internal suspend fun WhiteNoiseAppState.nativeAttachmentParkedForStorage(request: AttachmentTransferRequest): Boolean {
    val target = request.nativeTarget() ?: return false
    val state =
        runCatchingCancellable {
            marmotIo {
                attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target.toFfi()))
                    .items
                    .singleOrNull()
                    ?.state
            }
        }.getOrNull()
    return state == AttachmentTransferStateFfi.STORAGE_BLOCKED
}

/** The reader-facing identity of an attachment, the same however its native source is resolved. */
private fun AttachmentTransferRequest.parkKey(): String = "$accountRef|$groupIdHex|$messageIdHex|$attachmentIndex"

/** Records that the reader's own download of [request] is parked for storage; kept for this process only. */
internal fun WhiteNoiseAppState.recordReaderParkedDownload(request: AttachmentTransferRequest) {
    readerParkedDownloads += request.parkKey()
}

/**
 * Whether the reader's own earlier download of [request] is still parked for storage: it was recorded in this process,
 * and MDK still holds the body parked. MDK does not say which demand parked a body, and an automatic download is never
 * recorded, so reopening its chat never turns it into an explicit request against the reader's settings. A record
 * whose park has ended is dropped.
 */
internal suspend fun WhiteNoiseAppState.readerDownloadParkedForStorage(request: AttachmentTransferRequest): Boolean {
    val key = request.parkKey()
    if (key !in readerParkedDownloads) return false
    val parked = nativeAttachmentParkedForStorage(request)
    if (!parked) readerParkedDownloads -= key
    return parked
}
