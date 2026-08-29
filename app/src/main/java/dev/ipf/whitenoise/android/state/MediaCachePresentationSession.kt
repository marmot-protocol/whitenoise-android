package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaCachePresentationSession(
    val accountRef: String,
    val epoch: Int,
)

/** Main-safe L1 probe used to seed a returning file bubble without a frame gap. */
internal fun ConversationController.hasCachedAttachmentInMemory(
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean {
    val account = boundAccountRef ?: return false
    return appState.cachedMediaPlaintext(
        mediaCacheKey(account, group.groupIdHex, messageIdHex, attachmentIndex),
    ) != null
}

/** Reconcile presentation state with the encrypted L1/L2 cache. */
internal suspend fun ConversationController.refreshAttachmentTransferState(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    attachmentTransfers.refresh(attachmentTransferKey(messageIdHex, attachmentIndex)) {
        val account = boundAccountRef ?: return@refresh false
        appState.hasCachedAttachmentAfterHydration(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
            hydrateMemory = true,
        )
    }
}

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
        when {
            diskBytes != null ->
                withContext(Dispatchers.Main.immediate) {
                    val presentationCurrent = mediaCachePresentationSessionCurrent(presentationSession)
                    if (presentationCurrent) cacheMediaPlaintext(cacheKey, diskBytes)
                    presentationCurrent
                }
            !hydrateMemory && !initialMemoryHit ->
                withContext(Dispatchers.IO) {
                    diskMediaCache.containsAfterHydration(cacheKey)
                }
            else -> false
        }
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
