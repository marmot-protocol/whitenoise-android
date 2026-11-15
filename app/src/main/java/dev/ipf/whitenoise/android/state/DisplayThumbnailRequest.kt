package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.DisplayThumbnailSource
import dev.ipf.whitenoise.android.media.DisplayThumbnailToken
import dev.ipf.whitenoise.android.media.MediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private val displayThumbnailTracer = MarmotBridgeTracer()

/** A producer's immutable account/session and cache-generation fences, captured before any original read. */
internal data class DisplayThumbnailRequest(
    val source: DisplayThumbnailSource,
    val account: String,
    val sessionEpoch: Long,
    val generation: DisplayThumbnailToken,
)

/** Captures the displayed row's authoritative source revision without consulting protocol storage. */
internal fun ConversationController.displayThumbnailRequest(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): DisplayThumbnailRequest? {
    val account = boundAccountRef?.takeIf { it == appState.activeAccountRef } ?: return null
    val source =
        DisplayThumbnailSource(
            mediaCacheKey(account, group.groupIdHex, messageIdHex, attachmentIndex),
            reference.sourceEpoch,
            reference.ciphertextSha256,
        )
    return DisplayThumbnailRequest(
        source,
        account,
        appState.mediaUploadSessionEpoch(),
        appState.displayThumbnails.captureGeneration(source),
    )
}

/** Main-thread publication check rejects account switches and invalidation during disk or original decoding. */
internal fun WhiteNoiseAppState.displayThumbnailRequestCurrent(request: DisplayThumbnailRequest): Boolean =
    displayThumbnailSessionCurrent(request) && displayThumbnails.isCurrent(request.generation)

/** Account and session ownership remain independent of a source-specific cache eviction. */
internal fun WhiteNoiseAppState.displayThumbnailSessionCurrent(request: DisplayThumbnailRequest): Boolean =
    activeAccountRef == request.account && mediaUploadSessionEpoch() == request.sessionEpoch

/** Reads an expendable derivative off main; failures never request a transfer or alter download policy. */
internal suspend fun WhiteNoiseAppState.readDisplayThumbnail(
    request: DisplayThumbnailRequest,
    maxEdgePx: Int,
): MediaThumbnail? {
    val entry =
        displayThumbnailTracer.trace("WhiteNoise.displayThumbnail.read") {
            withContext(Dispatchers.IO) {
                runCatchingCancellable { displayThumbnails.read(request.source, maxEdgePx, request.generation) }.getOrNull()
            }
        }
    val current = entry?.takeIf { displayThumbnailRequestCurrent(request) }
    if (entry != null && current == null) entry.bitmap.recycle()
    displayThumbnailTracer.trace(
        if (current == null) "WhiteNoise.displayThumbnail.miss" else "WhiteNoise.displayThumbnail.hit",
    ) {}
    return current
}

/**
 * Waits for the single encoder within the presentation's lifetime, retaining only a weak bitmap while queued.
 * An L1 revisit retries deferred persistence; cancellation or source removal prevents late publication.
 */
internal suspend fun WhiteNoiseAppState.persistDisplayThumbnail(
    request: DisplayThumbnailRequest,
    pixels: WeakReference<Bitmap>,
    maxEdgePx: Int,
) {
    val owner = currentCoroutineContext().job
    displayThumbnailWritePermit.withPermit {
        if (!displayThumbnailRequestCurrent(request)) return@withPermit
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val existing = displayThumbnails.read(request.source, maxEdgePx, request.generation)
                if (existing != null) {
                    existing.bitmap.recycle()
                    return@runCatchingCancellable
                }
                val bitmap = pixels.get()?.takeUnless { it.isRecycled } ?: return@runCatchingCancellable
                displayThumbnailTracer.trace("WhiteNoise.displayThumbnail.write") {
                    displayThumbnails.write(request.source, MediaThumbnail(bitmap, maxEdgePx), request.generation) {
                        owner.isActive
                    }
                }
            }
        }
    }
}

/** All original-removal callers also retire every derivative size, including in-flight producers. Run off main. */
internal fun WhiteNoiseAppState.removeMediaDiskCacheEntry(key: String) {
    try {
        diskMediaCache.remove(key)
    } finally {
        displayThumbnails.remove(key)
    }
}

/** Expiry and group cleanup reach unloaded derivatives through the same persisted native ciphertext tags. */
internal fun WhiteNoiseAppState.removeMediaDiskCacheTags(tags: Set<String>) {
    try {
        diskMediaCache.removeByCiphertextTags(tags)
    } finally {
        displayThumbnails.removeByCiphertextTags(tags)
    }
}

/** Removes originals before the final derivative fence, including when original cleanup throws. */
internal fun WhiteNoiseAppState.clearMediaDiskCaches() {
    try {
        diskMediaCache.clear()
    } finally {
        displayThumbnails.clear()
    }
}

/** Reads MDK's current retention phase without materializing an original or using a conversation-memoized answer. */
internal suspend fun ConversationController.displayAttachmentNativeState(
    messageIdHex: String,
    attachmentIndex: Int,
): dev.ipf.marmotkit.AttachmentTransferStateFfi? {
    val request = attachmentTransferRequest(messageIdHex, attachmentIndex) ?: return null
    val target =
        appState.resolveNativeAttachmentTarget(
            request.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex)),
        ) ?: return null
    return appState.nativeAttachmentState(request.copy(sourceMessageIdHex = target.sourceMessageIdHex))
}

/** Confirms native original availability using metadata only, including while a thumbnail is already displayed. */
internal suspend fun ConversationController.hasDisplayAttachmentOriginal(
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean {
    if (hasCachedAttachment(messageIdHex, attachmentIndex)) return true
    val request = attachmentTransferRequest(messageIdHex, attachmentIndex) ?: return false
    return runCatchingCancellable {
        appState.hasNativeAttachment(request.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex)))
    }.getOrDefault(false)
}
