package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.media.imageAttachmentBytes
import dev.ipf.whitenoise.android.ui.conversation.media.shouldMaterializeAttachmentAutomatically
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ShortcodeInText = Regex(":[A-Za-z0-9_-]{1,64}:")

/**
 * NIP-30 emoji one message defines. Artwork comes through the attachment download
 * path under the automatic image policy and lives only as long as this composition.
 */
@Composable
internal fun rememberReceivedEmoji(
    item: TimelineMessage,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): ReceivedEmoji =
    key(
        controller,
        controller.boundAccountRef,
        item.record.messageIdHex,
        item.record.groupIdHex,
        item.record.tags,
        item.record.sourceEpoch,
        item.record.plaintext,
        item.projected?.media,
    ) {
        rememberReceivedEmojiInScope(item, controller, appState)
    }

/** A quote uses only its available target in the native visible window; never the replying row's art. */
@Composable
internal fun rememberReplyReceivedEmoji(
    targetMessageIdHex: String?,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): ReceivedEmoji {
    if (targetMessageIdHex == null) return ReceivedEmoji.None
    val window = controller.timeline
    val target =
        window.firstOrNull {
            it.record.messageIdHex == targetMessageIdHex &&
                it.record.messageIdHex !in controller.deletedMessageIds &&
                it.projected?.deleted != true
        }
    return target
        ?.takeUnless { controller.isRetainedRowGone(targetMessageIdHex) }
        ?.let { rememberReceivedEmoji(it, controller, appState) } ?: ReceivedEmoji.None
}

@Composable
private fun rememberReceivedEmojiInScope(
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
                .flatMap { (index, shortcodes) ->
                    val reference = attachments.accepted.first { it.index == index }.value
                    val art =
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
                        }
                    // Every alias of one image renders the same artwork.
                    shortcodes.mapNotNull { shortcode -> art?.let { shortcode to it } }
                }.toMap()
    }
    // Reaction chips stay literal `:code:` text until MDK can resolve a reaction's attachment (mdk#2151).
    return remember(defined, messageArt) {
        ReceivedEmoji(art = messageArt, attachmentIndexes = defined.keys)
    }
}

/** Which attachments the message's own emoji tags claim, with every alias each carries, keyed by index. */
@Composable
private fun rememberDefinedEmoji(
    controller: ConversationController,
    record: AppMessageRecordFfi,
    attachments: MessageAttachmentSet,
): Map<Int, List<String>> {
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
 * Whether a reaction's artwork may be fetched automatically. The reactor sent it, never the viewer's
 * own message, so the message's authorship must not widen the policy. Reaction artwork is gated off
 * until MDK can resolve a kind-7 attachment per message (mdk#2151), so nothing calls this yet.
 */
internal fun reactionArtworkAutoDownloadAllowed(
    mediaAutoDownloadAllowed: Boolean,
    automaticDownloadsPaused: Boolean,
): Boolean =
    shouldMaterializeAttachmentAutomatically(
        mine = false,
        mediaAutoDownloadAllowed = mediaAutoDownloadAllowed,
        automaticDownloadsPaused = automaticDownloadsPaused,
    )

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
