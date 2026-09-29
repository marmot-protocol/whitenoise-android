package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import dev.ipf.whitenoise.android.R

/** Display-only local artwork; alternate text retains authored copy, speech and selection offsets. */
internal object BuiltinEmoji {
    const val LITERAL_TAG = "builtin-emoji-literal"
    val shortcodes: List<String> = listOf(":marmot:", ":wn:")

    fun drawable(shortcode: String): Int? =
        when (shortcode) {
            ":marmot:" -> R.drawable.builtin_emoji_marmot
            ":wn:" -> R.drawable.builtin_emoji_wn
            else -> null
        }

    private val shortcodePattern = Regex(":marmot:|:wn:")
    private val inlineAnnotations =
        shortcodes.associateWith { shortcode ->
            buildAnnotatedString { appendInlineContent(shortcode, shortcode) }
                .getStringAnnotations(0, shortcode.length)
                .single()
        }

    // Legacy plaintext surfaces have no AST. Keep raw code delimiters and their contents literal.
    private val rawCodePattern =
        Regex(
            """(?m)^ {0,3}((?>`{3,}))[^\n`]*(?:\n|\z)[\s\S]*?(?:^ {0,3}\1`*[ \t]*\r?$|\z)|""" +
                """^ {0,3}((?>~{3,}))[^\n]*(?:\n|\z)[\s\S]*?(?:^ {0,3}\2~*[ \t]*\r?$|\z)|""" +
                """(`+)[\s\S]*?\3""",
        )

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
                if (codeRanges.any { start <= it.last && end > it.first } ||
                    text.getStringAnnotations(LITERAL_TAG, start, end).isNotEmpty() ||
                    text.getStringAnnotations(start, end).any { it.item == match.value } ||
                    text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && start < it.end && end > it.start }
                ) {
                    continue
                }
                // appendInlineContent uses the full shortcode as alternate text, never U+FFFC.
                val annotation = inlineAnnotations.getValue(match.value)
                addStringAnnotation(annotation.tag, annotation.item, start, end)
            }
        }
    }

    @Composable
    fun content(): Map<String, InlineTextContent> =
        remember {
            shortcodes.associateWith { shortcode ->
                InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Image(
                        painter = painterResource(requireNotNull(drawable(shortcode))),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
}
