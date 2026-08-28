package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaCachePresentationSession(
    val accountRef: String,
    val epoch: Int,
)

/**
 * Probes retained attachment plaintext without publishing a stale account's
 * disk result. Hydrating presentation callers promote authenticated L2 bytes
 * into L1 so the next composition can render them without another frame gap.
 */
internal suspend fun WhiteNoiseAppState.hasCachedAttachmentForPresentation(
    request: AttachmentTransferRequest,
    hydrateMemory: Boolean,
): Boolean {
    val presentationSession =
        if (hydrateMemory) {
            MediaCachePresentationSession(request.accountRef, mediaUploadSessionEpoch())
        } else {
            null
        }
    val cacheKey =
        mediaCacheKey(
            request.accountRef,
            request.groupIdHex,
            request.messageIdHex,
            request.attachmentIndex,
        )
    val (presentationCurrentAtStart, initialMemoryHit) =
        withContext(Dispatchers.Main.immediate) {
            val presentationCurrent = mediaCachePresentationSessionCurrent(presentationSession)
            presentationCurrent to (presentationCurrent && cachedMediaPlaintext(cacheKey) != null)
        }
    val diskBytes =
        if (!initialMemoryHit && hydrateMemory && presentationCurrentAtStart) {
            withContext(Dispatchers.IO) { diskMediaCache.get(cacheKey) }
        } else {
            null
        }
    val diskHit =
        withContext(Dispatchers.Main.immediate) {
            val presentationCurrent = mediaCachePresentationSessionCurrent(presentationSession)
            if (diskBytes != null && presentationCurrent) cacheMediaPlaintext(cacheKey, diskBytes)
            presentationCurrent && diskBytes != null
        } ||
            (
                !hydrateMemory &&
                    withContext(Dispatchers.IO) {
                        diskMediaCache.containsAfterHydration(cacheKey)
                    }
            )
    return withContext(Dispatchers.Main.immediate) {
        mediaCachePresentationSessionCurrent(presentationSession) &&
            (initialMemoryHit || diskHit || cachedMediaPlaintext(cacheKey) != null)
    }
}

private fun WhiteNoiseAppState.mediaCachePresentationSessionCurrent(session: MediaCachePresentationSession?): Boolean {
    assertMainThread { "mediaCachePresentationSessionCurrent" }
    return session == null ||
        (activeAccountRef == session.accountRef && mediaUploadSessionEpoch() == session.epoch)
}
