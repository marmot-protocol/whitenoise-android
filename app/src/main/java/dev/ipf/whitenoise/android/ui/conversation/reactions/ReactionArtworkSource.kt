package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
internal interface ReactionArtworkSource {
    /** Whether artwork may be fetched now. Read during composition, so turning downloads on retries missing art. */
    fun allowNetwork(): Boolean

    /** The decoded artwork of the reaction [reactionMessageIdHex], fetched only when [allowNetwork], or null. */
    suspend fun load(
        reactionMessageIdHex: String,
        allowNetwork: Boolean,
    ): EmojiArt?
}

/** The open conversation's reaction artwork, null outside a conversation, where pills stay literal text. */
internal val LocalReactionArtworkSource = staticCompositionLocalOf<ReactionArtworkSource?> { null }

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
    val allowNetwork = source?.allowNetwork() == true
    val shortcode = emoji?.takeIf { it.length > 2 && it.startsWith(':') && it.endsWith(':') }
    // Keyed by the reaction's identity, so a new reaction in the same pill starts empty in the same frame.
    val art = remember(source, shortcode, reactionMessageIdHex) { mutableStateOf<EmojiArt?>(null) }
    LaunchedEffect(art, allowNetwork) {
        if (source != null && shortcode != null && reactionMessageIdHex != null) {
            source.load(reactionMessageIdHex, allowNetwork)?.let { art.value = it }
        }
    }
    return art.value
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
 * download, the bytes are fetched only under the automatic-download policy for someone else's media, and decoded
 * images are reused while the conversation stays open.
 */
internal fun conversationReactionArtwork(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ReactionArtworkSource =
    object : ReactionArtworkSource {
        // A reaction's attachment never changes, so its decoded art is reused while the conversation stays open.
        private val decoded = LruCache<String, EmojiArt>(REACTION_ARTWORK_CACHE_ENTRIES)

        override fun allowNetwork(): Boolean =
            reactionArtworkAutoDownloadAllowed(
                mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
                automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused(),
            )

        override suspend fun load(
            reactionMessageIdHex: String,
            allowNetwork: Boolean,
        ): EmojiArt? =
            decoded.get(reactionMessageIdHex)
                ?: loadReactionArtwork(controller, reactionMessageIdHex, allowNetwork)?.also {
                    decoded.put(reactionMessageIdHex, it)
                }
    }

/** Decoded reaction images kept per open conversation, enough for a screenful of busy reaction rows. */
private const val REACTION_ARTWORK_CACHE_ENTRIES = 64

/**
 * Reads and decodes the image reaction [reactionId] carries, fetching it only when [allowNetwork], or null when it
 * has none or it cannot be read.
 */
private suspend fun loadReactionArtwork(
    controller: ConversationController,
    reactionId: String,
    allowNetwork: Boolean,
): EmojiArt? {
    val entry = controller.reactionAttachment(reactionId)
    val accepted =
        (entry?.attachment as? MediaAttachmentOutcomeFfi.Accepted)
            ?.takeIf { MediaReferenceSupport.isImageMedia(it.reference) }
    if (entry == null || accepted == null) return null
    // As for any reaction on iOS, the earliest reaction's artwork is read the same way whoever sent it.
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
        }.getOrNull()
    return bytes
        ?.let { withContext(Dispatchers.Default) { decodeEmojiImage(it, controller.mediaTrustDomain) } }
        ?.let(EmojiArt::Decoded)
}
