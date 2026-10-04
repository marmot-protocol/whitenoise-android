package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.toByteArray
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

/** Reads authenticated local bytes without allowing a missing or corrupt entry to initiate a native fetch. */
internal suspend fun ConversationController.cachedAttachmentPlaintext(
    messageIdHex: String,
    attachmentIndex: Int,
): ByteArray? {
    val account = boundAccountRef ?: return null
    val key = mediaCacheKey(account, group.groupIdHex, messageIdHex, attachmentIndex)
    return withContext(Dispatchers.Main.immediate) { appState.cachedMediaPlaintext(key) }
        ?: withContext(Dispatchers.IO) { appState.diskMediaCache.get(key) }
}

/**
 * Reads MDK-retained plaintext for a cache-only render, never starting a transfer.
 *
 * The host presentation caches can be empty while MDK still holds verified bytes, for example after the cache was
 * trimmed or for media received while no host copy was written. A result that finishes after an account or session
 * change is rejected.
 */
internal suspend fun ConversationController.retainedNativeAttachmentBytes(
    messageIdHex: String,
    attachmentIndex: Int,
): ByteArray? {
    val account = boundAccountRef ?: return null
    val request =
        AttachmentTransferRequest(
            account,
            group.groupIdHex,
            messageIdHex,
            attachmentIndex,
            sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex),
        )
    return appState.readRetainedAttachmentBytes(account) { appState.openNativeAttachment(request) }
}

/**
 * Materializes the plaintext [open] returns off the main thread and hands it back only while [accountRef] and the
 * media session epoch are still the live ones.
 *
 * A controller's bound account never changes after it is created, so it cannot detect a switch. The live active
 * account and the epoch are read on Main both before the open starts and after the bytes are in memory.
 */
internal suspend fun WhiteNoiseAppState.readRetainedAttachmentBytes(
    accountRef: String,
    open: suspend () -> AttachmentPlaintext?,
): ByteArray? {
    val session =
        withContext(Dispatchers.Main.immediate) {
            MediaCachePresentationSession(accountRef, mediaUploadSessionEpoch())
        }
    if (!withContext(Dispatchers.Main.immediate) { mediaCachePresentationSessionCurrent(session) }) return null
    val bytes = open()?.use { plaintext -> withContext(Dispatchers.IO) { plaintext.toByteArray() } }
    return bytes?.takeIf { withContext(Dispatchers.Main.immediate) { mediaCachePresentationSessionCurrent(session) } }
}

/** Reconcile presentation state with the encrypted L1/L2 cache. */
internal suspend fun ConversationController.refreshAttachmentTransferState(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    attachmentTransfers.refresh(attachmentTransferKey(messageIdHex, attachmentIndex)) {
        val account = boundAccountRef ?: return@refresh false
        appState.isAttachmentCachedForPresentation(
            AttachmentTransferRequest(
                account,
                group.groupIdHex,
                messageIdHex,
                attachmentIndex,
                sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex),
            ),
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
