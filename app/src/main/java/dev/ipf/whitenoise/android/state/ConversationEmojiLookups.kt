package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
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
 * The artwork each `:code:` reaction names, paired with the reaction event that carries it. MDK
 * names that event on the chip (`reactionMessageIdHex`) and `listMedia` returns its image, so the
 * lookup never scans reactions. One native read serves every chip; chips MDK named no event for,
 * or whose event has no accepted image, are absent and render as the literal shortcode.
 */
internal suspend fun ConversationController.reactionArtwork(events: Map<String, String>): Map<String, EmojiAttachment> {
    val account = boundAccountRef
    if (events.isEmpty() || account == null) {
        return emptyMap()
    }
    val media =
        runCatchingCancellable {
            appState.marmotIo(MarmotTraceSection.MEDIA_LIST) { listMedia(account, group.groupIdHex, null) }
        }.getOrNull().orEmpty()
    return reactionEmojiAttachmentsFrom(media, events)
}

/**
 * Matches [reactionMessageIds] (shortcode to reaction event id) to the image each event carries in
 * [media]. A shortcode only ever gets artwork from its own reaction event, so identical codes on
 * other reactions can never lend or borrow it.
 */
internal fun reactionEmojiAttachmentsFrom(
    media: List<MediaRecordFfi>,
    reactionMessageIds: Map<String, String>,
): Map<String, EmojiAttachment> =
    reactionMessageIds
        .mapNotNull { (shortcode, reactionId) ->
            val record =
                media.firstOrNull {
                    it.messageIdHex.equals(reactionId, ignoreCase = true) &&
                        MediaReferenceSupport.isImageMedia(it.reference)
                } ?: return@mapNotNull null
            shortcode to (reactionId to IndexedAttachment(record.attachmentIndex.toInt(), record.reference))
        }.toMap()
