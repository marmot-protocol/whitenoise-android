package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.reactionAttachment
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.media.imageAttachmentBytes
import dev.ipf.whitenoise.android.ui.conversation.messages.reactionArtworkAutoDownloadAllowed
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads the artwork one custom-emoji reaction carries, or null when it has none that can be shown. */
internal fun interface ReactionArtworkSource {
    /** The decoded artwork of the reaction [reactionMessageIdHex], or null. */
    suspend fun load(reactionMessageIdHex: String): EmojiArt?
}

/** The open conversation's reaction artwork; null outside a conversation, where pills stay literal text. */
internal val LocalReactionArtworkSource = staticCompositionLocalOf<ReactionArtworkSource?> { null }

/** Largest reaction artwork read for decoding; an emoji image never needs more, and a bigger one stays text. */
internal const val REACTION_ARTWORK_MAX_BYTES = 4 * 1024 * 1024

/**
 * The artwork for a `:shortcode:` pill, read from the reaction [reactionMessageIdHex] that defined it. Each reaction's
 * own attachment is used, so the same shortcode on another reaction never borrows it, and anything else stays null.
 */
@Composable
internal fun rememberReactionArtwork(
    emoji: String?,
    reactionMessageIdHex: String?,
): EmojiArt? {
    val source = LocalReactionArtworkSource.current
    val art by produceState<EmojiArt?>(null, source, emoji, reactionMessageIdHex) {
        val shortcode = emoji?.takeIf { it.length > 2 && it.startsWith(':') && it.endsWith(':') }
        value =
            if (source != null && shortcode != null && reactionMessageIdHex != null) {
                source.load(reactionMessageIdHex)
            } else {
                null
            }
    }
    return art
}

/** The pill's received emoji: its own reaction's artwork under [emoji], or none. */
@Composable
internal fun rememberReactionReceivedEmoji(
    emoji: String?,
    reactionMessageIdHex: String?,
): ReceivedEmoji {
    val art = rememberReactionArtwork(emoji, reactionMessageIdHex)
    return remember(emoji, art) {
        if (emoji != null && art != null) ReceivedEmoji(mapOf(emoji to art), emptySet()) else ReceivedEmoji.None
    }
}

/**
 * Reaction artwork for [controller]'s chat. MDK resolves the reaction's own encrypted image without starting a
 * download, and the bytes are fetched only under the automatic-download policy for someone else's media.
 */
internal fun conversationReactionArtwork(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ReactionArtworkSource = ReactionArtworkSource { reactionId -> loadReactionArtwork(appState, controller, reactionId) }

/** Reads and decodes the image reaction [reactionId] carries, or null when it has none or it cannot be read. */
private suspend fun loadReactionArtwork(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    reactionId: String,
): EmojiArt? {
    val entry = controller.reactionAttachment(reactionId)
    val accepted =
        (entry?.attachment as? MediaAttachmentOutcomeFfi.Accepted)
            ?.takeIf { MediaReferenceSupport.isImageMedia(it.reference) }
    if (entry == null || accepted == null) return null
    val allowNetwork =
        reactionArtworkAutoDownloadAllowed(
            mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused(),
        )
    val bytes =
        runCatchingCancellable {
            imageAttachmentBytes(
                controller = controller,
                messageIdHex = entry.messageIdHex,
                attachmentIndex = accepted.attachmentIndex.toInt(),
                reference = accepted.reference,
                mine = false,
                priority = AttachmentDownloadPriority.Automatic,
                allowNetwork = allowNetwork,
            )
        }.getOrNull()?.takeIf { it.size <= REACTION_ARTWORK_MAX_BYTES }
    return bytes
        ?.let { withContext(Dispatchers.Default) { decodeEmojiImage(it, controller.mediaTrustDomain) } }
        ?.let(EmojiArt::Decoded)
}
