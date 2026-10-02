package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

private const val REACTION_EVENT_KIND = 7uL

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
 * names that event on the chip (`reactionMessageIdHex`) and `listMedia` returns its images. The
 * event's own `emoji` tag then says which image is the shortcode's, exactly as for chat messages.
 * Neither read is bounded by message (MDK has no such query yet), so both are made only when the
 * first one finds a candidate image. Chips without a named event, an image or a matching tag are
 * absent and render as the literal shortcode.
 */
internal suspend fun ConversationController.reactionArtwork(events: Map<String, String>): Map<String, EmojiAttachment> {
    val account = boundAccountRef
    val media = if (events.isEmpty() || account == null) emptyList() else readGroupMedia(account)
    if (account == null || reactionEmojiAttachmentsFrom(media, events, null).isEmpty()) {
        return emptyMap()
    }
    return reactionEmojiAttachmentsFrom(media, events, readReactionTags(account))
}

/** The group's media records, or none when the native read fails. */
private suspend fun ConversationController.readGroupMedia(account: String): List<MediaRecordFfi> =
    runCatchingCancellable {
        appState.marmotIo(MarmotTraceSection.MEDIA_LIST) { listMedia(account, group.groupIdHex, null) }
    }.getOrNull().orEmpty()

/** Every reaction event's own tags, keyed by lowercase event id, or none when the native read fails. */
private suspend fun ConversationController.readReactionTags(account: String): Map<String, List<MessageTagFfi>> =
    runCatchingCancellable {
        appState.marmotIo { messages(account, group.groupIdHex, null, listOf(REACTION_EVENT_KIND)) }
    }.getOrNull().orEmpty().associate { it.messageIdHex.lowercase() to it.tags }

/**
 * Matches [reactionMessageIds] (shortcode to reaction event id) to the image of that event that its
 * own `emoji` tag names for the shortcode, by locator and with the first well-formed tag winning,
 * as for chat messages. With [tagsByEvent] null only candidates are reported (any image on the
 * event). A missing or unmatched tag yields nothing, and identical codes on other reactions can
 * never lend or borrow artwork.
 */
internal fun reactionEmojiAttachmentsFrom(
    media: List<MediaRecordFfi>,
    reactionMessageIds: Map<String, String>,
    tagsByEvent: Map<String, List<MessageTagFfi>>?,
): Map<String, EmojiAttachment> =
    reactionMessageIds
        .mapNotNull { (shortcode, reactionId) ->
            val images =
                media
                    .filter {
                        it.messageIdHex.equals(reactionId, ignoreCase = true) &&
                            MediaReferenceSupport.isImageMedia(it.reference)
                    }.map { IndexedValue(it.attachmentIndex.toInt(), it.reference) }
            val matched =
                if (tagsByEvent == null) {
                    images.firstOrNull()
                } else {
                    val tags = tagsByEvent[reactionId.lowercase()].orEmpty()
                    val named = Nip30Emoji.attachmentShortcodes(tags, images)
                    images.firstOrNull { named[it.index] == shortcode }
                }
            matched?.let { shortcode to (reactionId to it) }
        }.toMap()
