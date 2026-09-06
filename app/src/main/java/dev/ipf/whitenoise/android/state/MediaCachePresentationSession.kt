package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaCachePresentationSession(
    val accountRef: String,
    val epoch: Long,
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
        appState.isAttachmentCachedForPresentation(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
        )
    }
}

/**
 * Probes retained attachment availability without decrypting L2 bytes or
 * publishing a stale account's result after an account/session change.
 */
internal suspend fun WhiteNoiseAppState.isAttachmentCachedForPresentation(request: AttachmentTransferRequest): Boolean {
    val presentationSession =
        withContext(Dispatchers.Main.immediate) {
            MediaCachePresentationSession(request.accountRef, mediaUploadSessionEpoch())
        }
    val presentationCurrentAtStart =
        withContext(Dispatchers.Main.immediate) {
            mediaCachePresentationSessionCurrent(presentationSession)
        }
    if (!presentationCurrentAtStart) return false
    val cached = hasCachedAttachmentAfterHydration(request)
    return withContext(Dispatchers.Main.immediate) {
        mediaCachePresentationSessionCurrent(presentationSession) && cached
    }
}

private fun WhiteNoiseAppState.mediaCachePresentationSessionCurrent(session: MediaCachePresentationSession): Boolean {
    assertMainThread { "mediaCachePresentationSessionCurrent" }
    return activeAccountRef == session.accountRef && mediaUploadSessionEpoch() == session.epoch
}
