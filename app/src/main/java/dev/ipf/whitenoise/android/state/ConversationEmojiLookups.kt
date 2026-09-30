package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

private const val REACTION_EVENT_KIND = 7uL

// Reaction artwork is looked up among the group's newest reactions only.
private const val REACTION_EMOJI_SCAN_LIMIT = 500u

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
 * The image a recent `:code:` reaction in this group defined through its NIP-30 tag, paired with
 * the reaction event that carries it. Reaction summaries omit tags, so this reads the events.
 */
internal suspend fun ConversationController.reactionEmojiAttachment(shortcode: String): EmojiAttachment? {
    val account = boundAccountRef ?: return null
    val reactions =
        runCatchingCancellable {
            appState.marmotIo {
                messages(account, group.groupIdHex, REACTION_EMOJI_SCAN_LIMIT, listOf(REACTION_EVENT_KIND))
            }
        }.getOrNull()
            .orEmpty()
    // Newest first, so a redefined code shows its latest artwork.
    return reactions.asReversed().firstNotNullOfOrNull { reactionRecordEmoji(it, shortcode) }
}

/** The attachment a `:code:` reaction's own NIP-30 tag defines, keyed by its reaction event id. */
private fun reactionRecordEmoji(
    record: AppMessageRecordFfi,
    shortcode: String,
): EmojiAttachment? {
    if (record.plaintext != shortcode || !Nip30Emoji.hasEmojiTags(record.tags)) {
        return null
    }
    val references = MediaReferenceSupport.parseAllImetaTags(record.tags, record.sourceEpoch ?: 0uL)
    val attachments = MessageAttachments.indexed(references)
    val index =
        Nip30Emoji
            .attachmentShortcodes(record.tags, attachments)
            .entries
            .firstOrNull { it.value == shortcode }
            ?.key
    return attachments.firstOrNull { it.index == index }?.let { record.messageIdHex to it }
}
