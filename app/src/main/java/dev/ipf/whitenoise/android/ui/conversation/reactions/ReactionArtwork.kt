package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.reactionArtworkBytes
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.conversation.messages.reactionArtworkAutoDownloadAllowed
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes only each chip's native reaction source. Changing owner, source or policy immediately discards
 * the old composition's artwork; cancellation and the native read prevent late or revoked bytes from rendering.
 */
@Composable
internal fun rememberReactionArtwork(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    tallies: List<ReactionTally>,
    revision: ConversationWindowRevisionFfi? = null,
): Map<String, EmojiArt> {
    val sources =
        tallies
            .take(MAX_VISIBLE_REACTIONS)
            .filter { EmojiShortcodes.isShortcode(it.emoji) }
            .mapNotNull { it.reactionMessageIdHex }
            .distinct()
    val account = controller.boundAccountRef ?: return emptyMap()
    val group = controller.group.groupIdHex
    val generation = appState.runtimeGeneration
    val allowNetwork =
        reactionArtworkAutoDownloadAllowed(
            mediaAutoDownloadAllowed = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            automaticDownloadsPaused = appState.automaticAttachmentDownloadsPaused(),
        )
    return key(controller, account, group, generation, sources, allowNetwork, revision) {
        val artwork by produceState<Map<String, EmojiArt>>(emptyMap()) {
            for (source in sources) {
                val image =
                    runCatchingCancellable {
                        val entry =
                            appState.marmotIo { reactionAttachment(account, group, source) }
                                ?: return@runCatchingCancellable null
                        val bytes =
                            appState.reactionArtworkBytes(account, group, entry, allowNetwork)
                                ?: return@runCatchingCancellable null
                        withContext(Dispatchers.Default) { decodeEmojiImage(bytes, controller.mediaTrustDomain) }
                    }.getOrNull()
                if (image != null &&
                    appState.runtimeGeneration == generation &&
                    controller.acceptsConversationActionOwner(account, group)
                ) {
                    value = value + (source to EmojiArt.Decoded(image))
                }
            }
        }
        artwork
    }
}

/** Native chips use only their exact image; an unavailable definition stays literal rather than borrowing a local one. */
internal fun reactionInlineContent(
    shortcode: String?,
    artwork: EmojiArt?,
): Map<String, InlineTextContent> =
    if (shortcode == null || artwork == null) {
        emptyMap()
    } else {
        mapOf(
            shortcode to
                InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    EmojiArtImage(artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                },
        )
    }
