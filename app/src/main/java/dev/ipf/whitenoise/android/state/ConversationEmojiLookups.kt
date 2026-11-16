package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.MessageTagFfi

/** One message's own event tags, which conversation-window rows leave off; null when unreadable. */
internal suspend fun ConversationController.messageTags(messageIdHex: String): List<MessageTagFfi>? {
    val account = boundAccountRef ?: return null
    return runCatchingCancellable {
        appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
            timelineRowTags(account, group.groupIdHex, messageIdHex)
        }
    }.getOrNull()
}

/**
 * MDK's encrypted artwork for the custom-emoji reaction [reactionMessageIdHex] in this chat, matched by that
 * reaction's own `emoji` tag. It is a bounded local read that starts no download, and null when the reaction carries
 * no artwork or the read failed.
 */
internal suspend fun ConversationController.reactionAttachment(reactionMessageIdHex: String): AttachmentEntryFfi? {
    val account = boundAccountRef ?: return null
    return runCatchingCancellable {
        appState.marmotIo { reactionAttachment(account, group.groupIdHex, reactionMessageIdHex) }
    }.getOrNull()
}
