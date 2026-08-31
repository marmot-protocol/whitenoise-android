package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Returns the conversation-local coordinator key for one attachment slot. */
internal fun ConversationController.attachmentTransferKey(
    messageIdHex: String,
    attachmentIndex: Int,
): String = "$messageIdHex#$attachmentIndex"

/**
 * Drops decrypted bytes after a decoder or playback failure so the next open
 * retries the network path instead of repeatedly materializing corrupt media.
 */
internal suspend fun ConversationController.evictCachedAttachment(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    val account = boundAccountRef ?: return
    val cacheKey =
        dev.ipf.whitenoise.android.state.mediaCacheKey(
            account,
            group.groupIdHex,
            messageIdHex,
            attachmentIndex,
        )
    withContext(Dispatchers.Main.immediate) {
        appState.removeMediaMemoryCacheEntry(cacheKey)
    }
    withContext(Dispatchers.IO) { appState.diskMediaCache.remove(cacheKey) }
}

/**
 * Resolves an attachment as either bounded in-memory bytes or a private file
 * lease, preserving single-flight transfer behavior on cache misses.
 */
internal suspend fun ConversationController.downloadAttachmentSource(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    priority: AttachmentDownloadPriority,
): AttachmentPlaintext {
    val account = boundAccountRef ?: error("no active account")
    val request = AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex)
    return appState.downloadAttachmentPlaintextSource(
        request = request,
        reference = reference,
        priority = priority,
        onCacheMiss = {
            requestAttachmentTransfer(
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                priority = priority,
            ).await()
        },
    )
}
