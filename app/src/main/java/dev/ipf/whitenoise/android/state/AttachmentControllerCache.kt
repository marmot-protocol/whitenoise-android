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
    return appState.downloadAttachmentPlaintextSource(
        request = attachmentSourceRequest(account, messageIdHex, attachmentIndex),
        reference = reference,
        priority = priority,
    )
}

/**
 * Enqueues the durable interactive download for a confirmed attachment, as the byte-transfer path does,
 * so a streaming Open or Save that fails in the foreground still has durable work to wait for. A repeat
 * request coalesces onto the same transfer.
 */
internal fun ConversationController.enqueueInteractiveAttachmentDownload(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
) {
    val account = boundAccountRef ?: return
    if (reference.sourceEpoch == 0uL) return
    appState.enqueueAttachmentDownload(
        attachmentSourceRequest(account, messageIdHex, attachmentIndex),
        AttachmentDownloadPriority.Interactive,
    )
}

/** The durable identity of one attachment slot, keyed by its native source message when the projection has one. */
private fun ConversationController.attachmentSourceRequest(
    account: String,
    messageIdHex: String,
    attachmentIndex: Int,
): AttachmentTransferRequest =
    AttachmentTransferRequest(
        account,
        group.groupIdHex,
        messageIdHex,
        attachmentIndex,
        sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex),
    )

/** Returns the authoritative source id for a loaded projection, never the display id as a fallback. */
internal fun ConversationController.nativeAttachmentSourceId(messageIdHex: String): String? =
    timeline.firstOrNull { it.record.messageIdHex == messageIdHex }?.projected?.sourceMessageIdHex
        ?: timelineRecords[messageIdHex]?.sourceMessageIdHex
