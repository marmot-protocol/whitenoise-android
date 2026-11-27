package dev.ipf.whitenoise.android.ui

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.Nip30Emoji

/** Artwork one `:shortcode:` renders as. */
@Immutable
internal sealed interface EmojiArt {
    /** Artwork shipped in the APK. */
    data class Bundled(
        @DrawableRes val drawable: Int,
    ) : EmojiArt

    /** A decoded image: the user's own file or an attachment a message defined. */
    class Decoded(
        val image: ImageBitmap,
    ) : EmojiArt
}

/** Shortcodes this device defines from the user's files. */
internal val LocalCustomEmoji = compositionLocalOf { CustomEmojiSet.Empty }

/**
 * Shortcodes the exact surrounding message defines through NIP-30 `emoji` tags, and the message
 * attachments that carry them. Other messages, reactions and identity labels need their own source.
 */
@Immutable
internal class ReceivedEmoji(
    val art: Map<String, EmojiArt>,
    val attachmentIndexes: Set<Int>,
) {
    companion object {
        val None = ReceivedEmoji(emptyMap(), emptySet())
    }
}

internal val LocalReceivedEmoji = compositionLocalOf { ReceivedEmoji.None }

/**
 * `:shortcode:` emoji: bundled artwork, then user files and emoji a message defined.
 * Alternate text retains authored copy, speech and selection offsets.
 */
internal object EmojiShortcodes {
    const val LITERAL_TAG = "emoji-shortcode-literal"
    const val MAX_CODE_LENGTH = 64

    private class Builtin(
        @DrawableRes val drawable: Int,
        @StringRes val name: Int,
    )

    private val definitions =
        linkedMapOf(
            ":marmot:" to Builtin(R.drawable.builtin_emoji_marmot, R.string.emoji_name_marmot),
            ":wn:" to Builtin(R.drawable.builtin_emoji_wn, R.string.white_noise),
            ":marmot_classic:" to Builtin(R.drawable.builtin_emoji_marmot_classic, R.string.emoji_name_marmot_classic),
            ":marmot_wave:" to Builtin(R.drawable.builtin_emoji_marmot_wave, R.string.emoji_name_marmot_wave),
            ":marmot_yes:" to Builtin(R.drawable.builtin_emoji_marmot_yes, R.string.emoji_name_marmot_yes),
            ":marmot_laugh:" to Builtin(R.drawable.builtin_emoji_marmot_laugh, R.string.emoji_name_marmot_laugh),
            ":marmot_love:" to Builtin(R.drawable.builtin_emoji_marmot_love, R.string.emoji_name_marmot_love),
            ":marmot_party:" to Builtin(R.drawable.builtin_emoji_marmot_party, R.string.emoji_name_marmot_party),
            ":marmot_fire:" to Builtin(R.drawable.builtin_emoji_marmot_fire, R.string.emoji_name_marmot_fire),
            ":marmot_wow:" to Builtin(R.drawable.builtin_emoji_marmot_wow, R.string.emoji_name_marmot_wow),
            ":marmot_smirk:" to Builtin(R.drawable.builtin_emoji_marmot_smirk, R.string.emoji_name_marmot_smirk),
            ":marmot_cry:" to Builtin(R.drawable.builtin_emoji_marmot_cry, R.string.emoji_name_marmot_cry),
            ":marmot_angry:" to Builtin(R.drawable.builtin_emoji_marmot_angry, R.string.emoji_name_marmot_angry),
        )

    val builtins: List<String> = definitions.keys.toList()

    private val shortcodePattern = Regex(":[A-Za-z0-9_-]{1,$MAX_CODE_LENGTH}:")

    // appendInlineContent's tag is internal to Compose; read it back once.
    private val inlineContentTag =
        buildAnnotatedString { appendInlineContent(":x:", ":x:") }
            .getStringAnnotations(0, 3)
            .single()
            .tag

    fun isShortcode(text: String): Boolean = shortcodePattern.matches(text)

    /** Bundled pixel marmots, the classic marmot and the White Noise mark. */
    fun builtinDrawable(shortcode: String): Int? = definitions[shortcode]?.drawable

    /**
     * The square part of a bundled image's canvas that holds its artwork, or null when the artwork fills it.
     * Every renderer crops through this, so built-ins match Unicode and custom emoji in the app and in
     * notifications. Each square is the artwork's tight bounding box, centred and padded on its short side.
     */
    fun builtinArtworkRegion(
        @DrawableRes drawable: Int,
    ): IntRect? = artworkRegions[drawable]

    @Suppress("MagicNumber")
    private val artworkRegions: Map<Int, IntRect> =
        buildMap {
            /** A [side]-pixel square crop whose top-left corner is ([x], [y]). */
            fun square(
                x: Int,
                y: Int,
                side: Int,
            ) = IntRect(IntOffset(x, y), IntSize(side, side))
            // 128px pixel-art canvases with transparent margins around each pose.
            for (drawable in listOf(
                R.drawable.builtin_emoji_marmot,
                R.drawable.builtin_emoji_marmot_angry,
                R.drawable.builtin_emoji_marmot_cry,
                R.drawable.builtin_emoji_marmot_fire,
                R.drawable.builtin_emoji_marmot_laugh,
                R.drawable.builtin_emoji_marmot_smirk,
                R.drawable.builtin_emoji_marmot_wow,
            )) {
                put(drawable, square(12, 10, 104))
            }
            put(R.drawable.builtin_emoji_marmot_love, square(12, 18, 104))
            put(R.drawable.builtin_emoji_marmot_party, square(8, 0, 112))
            put(R.drawable.builtin_emoji_marmot_wave, square(4, 8, 112))
            put(R.drawable.builtin_emoji_marmot_yes, square(8, 12, 108))
            // The classic Marmot's 72px canvas includes padding around its 51px artwork.
            put(R.drawable.builtin_emoji_marmot_classic, square(11, 10, 51))
        }

    /** Localized spoken names, with product names kept untranslated. */
    @StringRes
    fun builtinName(shortcode: String): Int? = definitions[shortcode]?.name

    /**
     * Readable text for [emoji] on surfaces that can only show text, never the raw `:shortcode:`.
     * Unicode stays as is, built-ins use their product name and other shortcodes their words. A code
     * with no words, such as `:__:`, keeps its exact value so TalkBack never announces a blank.
     */
    fun plainLabel(
        emoji: String,
        resources: Resources,
    ): String =
        if (isShortcode(emoji)) {
            builtinName(emoji)?.let(resources::getString)
                ?: emoji
                    .trim(':')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .trim()
                    .ifBlank { emoji }
        } else {
            emoji
        }

    /** Bundled shortcodes keep their canonical artwork across local files and received attachments. */
    fun art(
        shortcode: String,
        custom: CustomEmojiSet,
        received: Map<String, EmojiArt>,
    ): EmojiArt? = builtinDrawable(shortcode)?.let(EmojiArt::Bundled) ?: custom[shortcode]?.art ?: received[shortcode]

    /**
     * Marks every `:shortcode:` outside code as inline content. Codes without artwork have no
     * entry in [content], so Compose draws their alternate text: the shortcode itself.
     */
    fun annotate(text: AnnotatedString): AnnotatedString {
        val matches = shortcodePattern.findAll(text.text).iterator()
        if (!matches.hasNext()) {
            return text
        }
        val outsideCode = Nip30Emoji.outsideRanges(Nip30Emoji.rawCodeRanges(text.text))
        return buildAnnotatedString {
            append(text)
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                val literal =
                    when {
                        !outsideCode(start, end) -> true
                        text.getStringAnnotations(LITERAL_TAG, start, end).isNotEmpty() -> true
                        text.getStringAnnotations(start, end).any { it.item == match.value } -> true
                        else ->
                            text.spanStyles.any {
                                it.item.fontFamily == FontFamily.Monospace && start < it.end && end > it.start
                            }
                    }
                if (literal) {
                    continue
                }
                // The full shortcode is the alternate text, never U+FFFC.
                addStringAnnotation(inlineContentTag, match.value, start, end)
            }
        }
    }

    /** Nonliteral shortcodes for non-Compose presentation; reuse the same code/link exclusions. */
    fun presentationShortcodes(text: String): List<String> = presentationRanges(text).map { it.item }.distinct()

    /** Each nonliteral shortcode in [text] with its range, in text order. */
    fun presentationRanges(text: String): List<AnnotatedString.Range<String>> =
        annotate(AnnotatedString(text))
            .getStringAnnotations(0, text.length)
            .filter { it.tag == inlineContentTag }
            .sortedBy { it.start }

    /** Inline artwork for every shortcode defined here, with user files taking precedence. */
    @Composable
    fun content(receivedEmoji: ReceivedEmoji = LocalReceivedEmoji.current): Map<String, InlineTextContent> {
        val custom = LocalCustomEmoji.current
        val received = receivedEmoji.art
        return remember(custom, received) {
            val codes = builtins + received.keys + custom.entries.map { it.shortcode }
            codes.associateWith { shortcode ->
                val art = requireNotNull(art(shortcode, custom, received))
                InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    EmojiArtImage(art, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** [EmojiShortcodes.plainLabel] in composition, so accessibility text never reads a raw `:shortcode:`. */
@Composable
internal fun emojiPlainLabel(emoji: String): String = EmojiShortcodes.plainLabel(emoji, LocalContext.current.resources)

/** Draws one emoji's artwork. */
@Suppress("FunctionNaming")
@Composable
internal fun EmojiArtImage(
    art: EmojiArt,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(rememberEmojiArtPainter(art), contentDescription, modifier)
}

/**
 * Reuses the same decoded artwork and bundled crop in an image or a decorative draw layer. [filterQuality] lets a
 * large rendering scale pixel art without smoothing; inline text keeps the default.
 */
@Composable
internal fun rememberEmojiArtPainter(
    art: EmojiArt,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
): Painter =
    when (art) {
        is EmojiArt.Bundled -> {
            val region = EmojiShortcodes.builtinArtworkRegion(art.drawable)
            if (region != null || filterQuality != DrawScope.DefaultFilterQuality) {
                val bitmap = ImageBitmap.imageResource(art.drawable)
                val source = region ?: IntRect(IntOffset.Zero, IntSize(bitmap.width, bitmap.height))
                remember(bitmap, source, filterQuality) {
                    BitmapPainter(bitmap, source.topLeft, source.size, filterQuality)
                }
            } else {
                painterResource(art.drawable)
            }
        }
        is EmojiArt.Decoded -> remember(art, filterQuality) { BitmapPainter(art.image, filterQuality = filterQuality) }
    }
