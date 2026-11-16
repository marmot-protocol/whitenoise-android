package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.conversation.messages.messageAttachmentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads one received original within the given byte limit. */
private typealias ReceivedEmojiReader = suspend (String, ConversationEmojiSource, Long) -> LocalEmojiArtwork

/**
 * Resolves definitions in text order. Each received original is read within the picker's per-image cap and the
 * remaining aggregate budget. One that is too large, unreadable or unverified stays literal text, while a lost
 * connection fails the lookup so the send can retry it.
 */
internal suspend fun resolveConversationEmojiArtwork(
    codes: List<String>,
    known: Map<String, LocalEmojiArtwork>,
    sources: Map<String, ConversationEmojiSource>,
    maxBytes: Long = EMOJI_SEND_MAX_BYTES.toLong(),
    maxImageBytes: Long = CustomEmojiStore.MAX_BYTES.toLong(),
    read: ReceivedEmojiReader,
): List<LocalEmojiArtwork> {
    require(maxBytes in 0..EMOJI_SEND_MAX_BYTES.toLong())
    val resolved = codes.distinct().filter { it in known || it in sources }
    val knownBytes = resolved.sumOf { known[it]?.bytes?.size?.toLong() ?: 0L }
    val limitFailure =
        when {
            resolved.size > Nip30Emoji.MAX_TAGS -> "too many custom emoji"
            knownBytes > maxBytes -> "too much custom emoji artwork for one message"
            else -> null
        }
    if (limitFailure != null) throw EmojiSendLimitException(limitFailure)
    var remaining = maxBytes - knownBytes
    return resolved.mapNotNull { code ->
        known[code] ?: readReceivedEmojiArtwork(code, sources.getValue(code), minOf(remaining, maxImageBytes), read)
            ?.also { remaining -= it.bytes.size }
    }
}

/** One received original within [limit] bytes, or null when it cannot be included and so stays literal text. */
private suspend fun readReceivedEmojiArtwork(
    code: String,
    source: ConversationEmojiSource,
    limit: Long,
    read: ReceivedEmojiReader,
): LocalEmojiArtwork? {
    val artwork =
        try {
            read(code, source, limit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            if (isConnectivityFailure(failure)) throw failure
            null
        }
    return artwork?.takeIf { it.bytes.size <= limit }
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
        readConversationEmojiArtwork(code, source, AttachmentDownloadPriority.Interactive, maxBytes)
    }
}

/** One loaded message's original artwork, retained only by the conversation's transient picker. */
internal data class ConversationEmojiSource(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
)

/** The inputs a loaded row's received definitions depend on, so unrelated row updates reuse its lookup. */
internal data class EmojiSourceRow(
    val messageIdHex: String,
    val plaintext: String,
    val tags: List<MessageTagFfi>,
    val images: List<IndexedAttachment>,
)

/** Per-row definitions already read, keyed by the inputs they were derived from. */
internal typealias EmojiSourceDefinitions = MutableMap<EmojiSourceRow, Map<Int, List<String>>>

/** Loaded rows whose text names a non-built-in code and whose own images could define it; built-ins always win. */
internal fun ConversationController.emojiSourceRows(): List<EmojiSourceRow> =
    timeline.mapNotNull { item ->
        val record = item.record
        val candidate =
            record.messageIdHex !in deletedMessageIds &&
                item.projected?.deleted != true &&
                !isRetainedRowGone(record.messageIdHex) &&
                Nip30Emoji.shortcodesIn(record.plaintext).any { it !in EmojiShortcodes.builtins }
        if (!candidate) return@mapNotNull null
        val images =
            messageAttachmentSet(record.tags, record.sourceEpoch, item.projected?.media)
                .accepted
                .filter { MediaReferenceSupport.isImageMedia(it.value) }
        if (images.isEmpty()) null else EmojiSourceRow(record.messageIdHex, record.plaintext, record.tags, images)
    }

/**
 * Derives non-built-in shortcode sources from each row's own tags and original attachment indices. A row's
 * native tag read is reused from [definitions] while its inputs are unchanged.
 */
internal suspend fun ConversationController.conversationEmojiSources(
    rows: List<EmojiSourceRow> = emojiSourceRows(),
    definitions: EmojiSourceDefinitions = HashMap(),
): Map<String, ConversationEmojiSource> {
    definitions.keys.retainAll(rows.toSet())
    val sources = linkedMapOf<String, ConversationEmojiSource>()
    for (row in rows) {
        val defined = definitions[row] ?: emojiDefinitions(row)?.also { definitions[row] = it } ?: continue
        for ((index, reference) in row.images) {
            defined[index].orEmpty().filterNot { it in EmojiShortcodes.builtins }.forEach { code ->
                sources.putIfAbsent(code, ConversationEmojiSource(row.messageIdHex, index, reference))
            }
        }
    }
    return sources
}

/** The row's per-image definitions, or null while its tags are unreadable so a later pass retries them. */
private suspend fun ConversationController.emojiDefinitions(row: EmojiSourceRow): Map<Int, List<String>>? {
    val tags = if (Nip30Emoji.hasEmojiTags(row.tags)) row.tags else messageTags(row.messageIdHex) ?: return null
    return Nip30Emoji.attachmentShortcodes(tags, row.images)
}

/** Reuses the attachment owner and authenticates original pixels before uploading them under the current epoch. */
internal suspend fun ConversationController.readConversationEmojiArtwork(
    shortcode: String,
    source: ConversationEmojiSource,
    priority: AttachmentDownloadPriority,
    maxBytes: Long = EMOJI_SEND_MAX_BYTES.toLong(),
): LocalEmojiArtwork {
    val reference = source.reference
    val bytes =
        downloadAttachment(
            source.messageIdHex,
            source.attachmentIndex,
            reference,
            priority,
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
