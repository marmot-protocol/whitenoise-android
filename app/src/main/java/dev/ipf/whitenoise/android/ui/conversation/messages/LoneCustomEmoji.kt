package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.conversation.stickers.StickerMetrics
import dev.ipf.whitenoise.android.ui.emojiPlainLabel
import dev.ipf.whitenoise.android.ui.rememberEmojiArtPainter

/** The one custom emoji a message consists of, with the artwork it resolved to. */
internal class LoneCustomEmoji(
    val shortcode: String,
    val art: EmojiArt,
)

/** A message that is exactly one custom emoji shows it at sticker size in place of its bubble. */
internal object LoneCustomEmojiPresentation {
    /** The same side as a stand-alone sticker. */
    const val SIDE_DP = StickerMetrics.MESSAGE_SIDE_DP

    /**
     * The emoji [text] consists of once [resolve] has its artwork, or null to keep the ordinary bubble: when the
     * message cannot stand alone (a reply, media, a sticker), for any other text, and while the artwork is still
     * loading or failed to load.
     */
    fun of(
        text: String,
        standsAlone: Boolean,
        resolve: (String) -> EmojiArt?,
    ): LoneCustomEmoji? {
        val shortcode = text.trim().takeIf { standsAlone && EmojiShortcodes.isShortcode(it) } ?: return null
        return resolve(shortcode)?.let { LoneCustomEmoji(shortcode, it) }
    }
}

/** Draws a lone custom emoji at sticker size; bundled pixel art scales up without smoothing. */
@Suppress("FunctionNaming")
@Composable
internal fun LoneCustomEmojiImage(
    emoji: LoneCustomEmoji,
    modifier: Modifier = Modifier,
) {
    val quality = if (emoji.art is EmojiArt.Bundled) FilterQuality.None else FilterQuality.High
    Image(
        painter = rememberEmojiArtPainter(emoji.art, quality),
        contentDescription = emojiPlainLabel(emoji.shortcode),
        modifier = modifier.size(LoneCustomEmojiPresentation.SIDE_DP.dp),
    )
}
