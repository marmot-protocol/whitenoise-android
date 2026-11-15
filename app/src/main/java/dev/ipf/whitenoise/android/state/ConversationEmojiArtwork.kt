package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.ui.conversation.messages.messageAttachmentSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves definitions in text order, bounding each additional original by the remaining aggregate budget. */
internal suspend fun resolveConversationEmojiArtwork(
    codes: List<String>,
    known: Map<String, LocalEmojiArtwork>,
    sources: Map<String, ConversationEmojiSource>,
    maxBytes: Long = EMOJI_SEND_MAX_BYTES.toLong(),
    read: suspend (String, ConversationEmojiSource, Long) -> LocalEmojiArtwork,
): List<LocalEmojiArtwork> {
    require(maxBytes in 0..EMOJI_SEND_MAX_BYTES.toLong())
    val resolved = codes.distinct().filter { it in known || it in sources }
    if (resolved.size > Nip30Emoji.MAX_TAGS) throw EmojiSendLimitException("too many custom emoji")
    val knownBytes = resolved.sumOf { known[it]?.bytes?.size?.toLong() ?: 0L }
    if (knownBytes > maxBytes) throw EmojiSendLimitException("too much custom emoji artwork for one message")
    var remaining = maxBytes - knownBytes
    return resolved.map { code ->
        known[code] ?: run {
            val artwork =
                try {
                    read(code, sources.getValue(code), remaining)
                } catch (_: AttachmentTooLargeToPresentException) {
                    throw EmojiSendLimitException("too much custom emoji artwork for one message")
                }
            if (artwork.bytes.size > remaining) {
                throw EmojiSendLimitException("too much custom emoji artwork for one message")
            }
            remaining -= artwork.bytes.size
            artwork
        }
    }
}

/** Combines canonical, local, installed and loaded-message definitions within one bounded send. */
internal suspend fun ConversationController.emojiArtworkIncludingConversation(
    account: String,
    text: String,
): List<LocalEmojiArtwork> {
    val known = appState.emojiArtworkIn(account, text).associateBy { it.shortcode }
    val codes = Nip30Emoji.shortcodesIn(text)
    val sources = if (codes.all(known::containsKey)) emptyMap() else conversationEmojiSources()
    return resolveConversationEmojiArtwork(codes, known, sources) { code, source, maxBytes ->
        readConversationEmojiArtwork(code, source, maxBytes)
    }
}

/** One loaded message's original artwork, retained only by the conversation's transient picker. */
internal data class ConversationEmojiSource(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
)

/** Derives shortcode sources from each loaded message's own tags and original attachment indices. */
internal suspend fun ConversationController.conversationEmojiSources(): Map<String, ConversationEmojiSource> {
    val sources = linkedMapOf<String, ConversationEmojiSource>()
    val rows =
        timeline.toList().filter { item ->
            item.record.messageIdHex !in deletedMessageIds &&
                item.projected?.deleted != true &&
                !isRetainedRowGone(item.record.messageIdHex) &&
                Nip30Emoji.shortcodesIn(item.record.plaintext).isNotEmpty()
        }
    for (item in rows) {
        val record = item.record
        val images =
            messageAttachmentSet(record.tags, record.sourceEpoch, item.projected?.media)
                .accepted
                .filter { MediaReferenceSupport.isImageMedia(it.value) }
        if (images.isEmpty()) continue
        val tags = if (Nip30Emoji.hasEmojiTags(record.tags)) record.tags else messageTags(record.messageIdHex).orEmpty()
        val defined = Nip30Emoji.attachmentShortcodes(tags, images)
        for ((index, reference) in images) {
            defined[index].orEmpty().forEach { code ->
                sources.putIfAbsent(code, ConversationEmojiSource(record.messageIdHex, index, reference))
            }
        }
    }
    return sources
}

/** Reuses the attachment owner and authenticates original pixels before uploading them under the current epoch. */
internal suspend fun ConversationController.readConversationEmojiArtwork(
    shortcode: String,
    source: ConversationEmojiSource,
    maxBytes: Long = EMOJI_SEND_MAX_BYTES.toLong(),
): LocalEmojiArtwork {
    val reference = source.reference
    val bytes =
        downloadAttachment(
            source.messageIdHex,
            source.attachmentIndex,
            reference,
            AttachmentDownloadPriority.Automatic,
            maxBytes = maxBytes,
        )
    val artwork =
        LocalEmojiArtwork(
            shortcode,
            reference.fileName,
            reference.mediaType,
            bytes,
            reference.dim,
            reference.thumbhash,
        )
    withContext(Dispatchers.Default) {
        check(artwork.sha256.equals(reference.plaintextSha256, ignoreCase = true)) { "emoji plaintext hash mismatch" }
    }
    return artwork
}
