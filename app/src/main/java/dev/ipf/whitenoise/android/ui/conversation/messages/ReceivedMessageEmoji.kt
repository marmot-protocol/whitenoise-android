package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.Nip30Emoji
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.messageTags
import dev.ipf.whitenoise.android.state.reactionArtwork
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.media.imageAttachmentBytes
import dev.ipf.whitenoise.android.ui.conversation.media.shouldMaterializeAttachmentAutomatically
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ShortcodeInText = Regex(":[A-Za-z0-9_-]{1,64}:")

/**
 * NIP-30 emoji one message and its reactions define. Artwork comes through the attachment download
 * path under the automatic image policy and lives only as long as this composition.
 */
@Composable
internal fun rememberReceivedEmoji(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): ReceivedEmoji {
    val record = item.record
    val attachments =
        rememberMessageAttachments(record.tags, record.messageIdHex, record.sourceEpoch, item.projected?.media)
    val mine = controller.isMessageMine(record)
    val allowNetwork =
        shouldMaterializeAttachmentAutomatically(
            mine = mine,
            mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused(),
        )
    val defined = rememberDefinedEmoji(controller, record, attachments)
    val messageArt by produceState(emptyMap<String, EmojiArt>(), record.messageIdHex, defined, allowNetwork) {
        value =
            defined
                .mapNotNull { (index, shortcode) ->
                    val reference = attachments.accepted.first { it.index == index }.value
                    emojiArt {
                        imageAttachmentBytes(
                            controller = controller,
                            messageIdHex = record.messageIdHex,
                            attachmentIndex = index,
                            reference = reference,
                            mine = mine,
                            priority = AttachmentDownloadPriority.Automatic,
                            allowNetwork = allowNetwork,
                        )
                    }?.let { shortcode to it }
                }.toMap()
    }
    val reactionArt = rememberReactionEmojiArt(controller, record.messageIdHex, messageArt, allowNetwork)
    return remember(defined, messageArt, reactionArt) {
        ReceivedEmoji(art = reactionArt + messageArt, attachmentIndexes = defined.keys)
    }
}

/** Which attachments the message's own emoji tags claim, keyed by attachment index. */
@Composable
private fun rememberDefinedEmoji(
    controller: ConversationController,
    record: AppMessageRecordFfi,
    attachments: MessageAttachmentSet,
): Map<Int, String> {
    // Conversation-window rows omit event tags, so only a message that could define emoji is re-read.
    val mayDefineEmoji =
        remember(record.plaintext, attachments) {
            attachments.accepted.any { MediaReferenceSupport.isImageMedia(it.value) } &&
                ShortcodeInText.containsMatchIn(record.plaintext)
        }
    val defined by produceState(emptyMap(), record.messageIdHex, record.tags, attachments, mayDefineEmoji) {
        val tags =
            when {
                Nip30Emoji.hasEmojiTags(record.tags) -> record.tags
                mayDefineEmoji -> controller.messageTags(record.messageIdHex).orEmpty()
                else -> emptyList()
            }
        value = Nip30Emoji.attachmentShortcodes(tags, attachments.accepted)
    }
    return defined
}

/**
 * Artwork for `:code:` reactions on this message that nothing local or in the message defines.
 * MDK names each chip's artwork event (`reactionMessageIdHex`), so a reaction update or removal
 * changes the keys below and the lookup restarts for exactly that chip.
 */
@Composable
private fun rememberReactionEmojiArt(
    controller: ConversationController,
    messageIdHex: String,
    messageArt: Map<String, EmojiArt>,
    allowNetwork: Boolean,
): Map<String, EmojiArt> {
    val custom = LocalCustomEmoji.current
    val reactionEvents =
        controller.reactions[messageIdHex]
            .orEmpty()
            .filter {
                EmojiShortcodes.isShortcode(it.emoji) && EmojiShortcodes.art(it.emoji, custom, messageArt) == null
            }.mapNotNull { tally -> tally.reactionMessageIdHex?.let { tally.emoji to it } }
            .toMap()
    val reactionArt by produceState(emptyMap(), messageIdHex, reactionEvents, allowNetwork) {
        val attachments = controller.reactionArtwork(reactionEvents)
        value =
            attachments
                .mapNotNull { (shortcode, found) ->
                    val (eventIdHex, attachment) = found
                    emojiArt {
                        imageAttachmentBytes(
                            controller = controller,
                            messageIdHex = eventIdHex,
                            attachmentIndex = attachment.index,
                            reference = attachment.value,
                            mine = false,
                            priority = AttachmentDownloadPriority.Automatic,
                            allowNetwork = allowNetwork,
                        )
                    }?.let { shortcode to it }
                }.toMap()
    }
    return reactionArt
}

/** Decoded artwork, or null when the bytes are unavailable under policy or do not decode. */
private suspend fun emojiArt(bytes: suspend () -> ByteArray?): EmojiArt? {
    val data =
        try {
            bytes()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    return data?.let { withContext(Dispatchers.Default) { decodeEmojiImage(it) } }?.let(EmojiArt::Decoded)
}

/** The message's attachments minus the ones that are inline emoji artwork. */
internal fun MessageAttachmentSet.withoutEmoji(received: ReceivedEmoji): MessageAttachmentSet {
    if (received.attachmentIndexes.isEmpty()) {
        return this
    }
    return MessageAttachmentSet(accepted.filter { it.index !in received.attachmentIndexes }, rejected)
}
