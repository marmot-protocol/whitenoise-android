package dev.ipf.whitenoise.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
 * Shortcodes the surrounding message and its reactions define through NIP-30 `emoji` tags, and the
 * message attachments that carry them, which render inline instead of as files.
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
 * `:shortcode:` emoji: user files, then emoji a message defined, then built-in artwork.
 * Alternate text retains authored copy, speech and selection offsets.
 */
internal object EmojiShortcodes {
    const val LITERAL_TAG = "emoji-shortcode-literal"
    const val MAX_CODE_LENGTH = 64
    val builtins: List<String> = listOf(":marmot:", ":wn:")

    private val shortcodePattern = Regex(":[A-Za-z0-9_-]{1,$MAX_CODE_LENGTH}:")

    // appendInlineContent's tag is internal to Compose; read it back once.
    private val inlineContentTag =
        buildAnnotatedString { appendInlineContent(":x:", ":x:") }
            .getStringAnnotations(0, 3)
            .single()
            .tag

    private val rawCodePattern = Nip30Emoji.rawCodePattern

    fun isShortcode(text: String): Boolean = shortcodePattern.matches(text)

    fun builtinDrawable(shortcode: String): Int? =
        when (shortcode) {
            ":marmot:" -> R.drawable.builtin_emoji_marmot
            ":wn:" -> R.drawable.builtin_emoji_wn
            else -> null
        }

    /** The artwork [shortcode] renders as: user file, then received attachment, then built-in. */
    fun art(
        shortcode: String,
        custom: CustomEmojiSet,
        received: Map<String, EmojiArt>,
    ): EmojiArt? = custom[shortcode]?.art ?: received[shortcode] ?: builtinDrawable(shortcode)?.let(EmojiArt::Bundled)

    /**
     * Marks every `:shortcode:` outside code as inline content. Codes without artwork have no
     * entry in [content], so Compose draws their alternate text: the shortcode itself.
     */
    fun annotate(text: AnnotatedString): AnnotatedString {
        val matches = shortcodePattern.findAll(text.text).iterator()
        if (!matches.hasNext()) {
            return text
        }
        val codeRanges = rawCodePattern.findAll(text.text).map { it.range }.toList()
        return buildAnnotatedString {
            append(text)
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                val literal =
                    when {
                        codeRanges.any { start <= it.last && end > it.first } -> true
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

    /** Inline artwork for every shortcode defined here, with user files taking precedence. */
    @Composable
    fun content(): Map<String, InlineTextContent> {
        val custom = LocalCustomEmoji.current
        val received = LocalReceivedEmoji.current.art
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

/** Draws one emoji's artwork. */
@Suppress("FunctionNaming")
@Composable
internal fun EmojiArtImage(
    art: EmojiArt,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    when (art) {
        is EmojiArt.Bundled -> Image(painterResource(art.drawable), contentDescription, modifier)
        is EmojiArt.Decoded -> Image(art.image, contentDescription, modifier)
    }
}
