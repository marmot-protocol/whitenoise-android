package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.DisplayThumbnailSource
import dev.ipf.whitenoise.android.media.MediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A producer's immutable account/session and cache-generation fences, captured before any original read. */
internal data class DisplayThumbnailRequest(
    val source: DisplayThumbnailSource,
    val account: String,
    val sessionEpoch: Long,
    val generation: Long,
)

/** Captures the displayed row's authoritative source revision without consulting protocol storage. */
internal fun ConversationController.displayThumbnailRequest(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): DisplayThumbnailRequest? {
    val account = boundAccountRef ?: return null
    if (appState.activeAccountRef != account) return null
    return DisplayThumbnailRequest(
        DisplayThumbnailSource(
            mediaCacheKey(account, group.groupIdHex, messageIdHex, attachmentIndex),
            reference.sourceEpoch,
            reference.ciphertextSha256,
        ),
        account,
        appState.mediaUploadSessionEpoch(),
        appState.displayThumbnails.captureGeneration(),
    )
}

/** Main-thread publication check rejects account switches and invalidation during disk or original decoding. */
internal fun WhiteNoiseAppState.displayThumbnailRequestCurrent(request: DisplayThumbnailRequest): Boolean =
    activeAccountRef == request.account && mediaUploadSessionEpoch() == request.sessionEpoch &&
        displayThumbnails.isCurrent(request.generation)

/** Reads an expendable derivative off main; failures never request a transfer or alter download policy. */
internal suspend fun WhiteNoiseAppState.readDisplayThumbnail(
    request: DisplayThumbnailRequest,
    maxEdgePx: Int,
): MediaThumbnail? {
    val entry =
        withContext(Dispatchers.IO) {
            runCatchingCancellable { displayThumbnails.read(request.source, maxEdgePx, request.generation) }.getOrNull()
        } ?: return null
    if (displayThumbnailRequestCurrent(request)) return entry
    entry.bitmap.recycle()
    return null
}

/** Writes after display publication, at most one outstanding encoder, without taking ownership of shared pixels. */
internal fun WhiteNoiseAppState.persistDisplayThumbnail(
    request: DisplayThumbnailRequest,
    bitmap: Bitmap,
    maxEdgePx: Int,
) {
    if (!displayThumbnailRequestCurrent(request) || !displayThumbnailWritePermit.tryAcquire()) return
    mutationsScope.launch(Dispatchers.IO) {
        try {
            runCatchingCancellable {
                displayThumbnails.write(request.source, MediaThumbnail(bitmap, maxEdgePx), request.generation)
            }
        } finally {
            displayThumbnailWritePermit.release()
        }
    }
}

/** All original-removal callers also retire every derivative size, including in-flight producers. Run off main. */
internal fun WhiteNoiseAppState.removeMediaDiskCacheEntry(key: String) {
    try {
        displayThumbnails.remove(key)
    } finally {
        diskMediaCache.remove(key)
    }
}

/** Expiry and group cleanup reach unloaded derivatives through the same persisted native ciphertext tags. */
internal fun WhiteNoiseAppState.removeMediaDiskCacheTags(tags: Set<String>) {
    try {
        displayThumbnails.removeByCiphertextTags(tags)
    } finally {
        diskMediaCache.removeByCiphertextTags(tags)
    }
}

/** Clears both encrypted tiers independently, preserving the original cleanup even if derivative I/O fails. */
internal fun WhiteNoiseAppState.clearMediaDiskCaches() {
    try {
        displayThumbnails.clear()
    } finally {
        diskMediaCache.clear()
    }
}
