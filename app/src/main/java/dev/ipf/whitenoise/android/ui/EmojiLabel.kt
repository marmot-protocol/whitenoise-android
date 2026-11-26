package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** Authored identity/description text has local artwork, never the surrounding message's emoji tags. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun EmojiLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
    suffix: String = "",
) {
    val custom = LocalCustomEmoji.current
    val hasArtwork =
        remember(text, custom) {
            ':' in text && EmojiShortcodes.presentationShortcodes(text).any {
                EmojiShortcodes.art(it, custom, emptyMap()) != null
            }
        }
    if (!hasArtwork) {
        Text(
            text = text + suffix,
            modifier = modifier,
            color = color,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            style = style,
        )
        return
    }
    val resources = LocalContext.current.resources
    Text(
        text =
            remember(text, suffix, custom, resources) {
                val label = EmojiShortcodes.annotate(AnnotatedString(text)).withSpokenEmojiNames(custom, resources)
                if (suffix.isEmpty()) {
                    label
                } else {
                    buildAnnotatedString {
                        append(label)
                        append(suffix)
                    }
                }
            },
        inlineContent = EmojiShortcodes.content(receivedEmoji = ReceivedEmoji.None),
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = style,
    )
}
