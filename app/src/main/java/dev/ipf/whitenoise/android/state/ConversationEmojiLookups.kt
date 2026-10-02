package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

/** A reaction event id and the attachment in it that carries the emoji artwork. */
internal typealias EmojiAttachment = Pair<String, IndexedAttachment>

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
 * Matches [reactionMessageIds] (shortcode to reaction event id) to the image of that event which its
 * own `emoji` tag names for the shortcode, by locator and with the first well-formed tag winning,
 * as for chat messages. A missing or unmatched tag yields nothing, and identical codes on other
 * reactions can never lend or borrow artwork. Reaction artwork is gated off until MDK can read a
 * reaction's attachment per message (mdk#2151), so nothing calls this yet. It stays so adopting
 * that query is a small change.
 */
internal fun reactionEmojiAttachmentsFrom(
    media: List<MediaRecordFfi>,
    reactionMessageIds: Map<String, String>,
    tagsByEvent: Map<String, List<MessageTagFfi>>,
): Map<String, EmojiAttachment> =
    reactionMessageIds
        .mapNotNull { (shortcode, reactionId) ->
            val images =
                media
                    .filter {
                        it.messageIdHex.equals(reactionId, ignoreCase = true) &&
                            MediaReferenceSupport.isImageMedia(it.reference)
                    }.map { IndexedValue(it.attachmentIndex.toInt(), it.reference) }
            val named = Nip30Emoji.attachmentShortcodes(tagsByEvent[reactionId.lowercase()].orEmpty(), images)
            images.firstOrNull { named[it.index] == shortcode }?.let { shortcode to (reactionId to it) }
        }.toMap()
