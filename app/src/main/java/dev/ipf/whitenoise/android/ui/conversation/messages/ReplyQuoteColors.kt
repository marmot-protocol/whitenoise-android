package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio

private const val REPLY_QUOTE_FILL_ALPHA = 0.16f
private const val REPLY_QUOTE_SECONDARY_ALPHA = 0.78f

/** Container and secondary-text roles shared by the message reply-card boundaries. */
internal data class ReplyQuoteColors(
    val container: Color,
    val secondary: Color,
)

/** Keeps default and AMOLED reply roles intact while giving custom filled bubbles a readable inset. */
internal fun messageReplyQuoteColors(
    customBubbleColorActive: Boolean,
    outgoingReply: Boolean,
    background: Color,
    content: Color,
    colorScheme: ColorScheme,
): ReplyQuoteColors =
    when {
        customBubbleColorActive -> customReplyQuoteColors(background, content)
        outgoingReply ->
            ReplyQuoteColors(
                container = content.copy(alpha = REPLY_QUOTE_FILL_ALPHA),
                secondary = content.copy(alpha = REPLY_QUOTE_SECONDARY_ALPHA),
            )
        else -> ReplyQuoteColors(container = colorScheme.surface, secondary = colorScheme.onSurfaceVariant)
    }

/**
 * Prefer a darker inset. Lighten pure black or a palette where darkening would make the existing
 * text unreadable. Resolve the fill before checking secondary text so alpha is measured as drawn.
 * AMOLED and semantic failure/deletion colors keep their existing presentation at the caller.
 */
private fun customReplyQuoteColors(
    background: Color,
    content: Color,
): ReplyQuoteColors {
    val darker = Color.Black.copy(alpha = REPLY_QUOTE_FILL_ALPHA).compositeOver(background)
    val container =
        if (darker.toArgb() != background.toArgb() && replyTextContrast(content, darker) >= WCAG_AA_NORMAL_TEXT_CONTRAST) {
            darker
        } else {
            Color.White.copy(alpha = REPLY_QUOTE_FILL_ALPHA).compositeOver(background)
        }
    val muted = content.copy(alpha = REPLY_QUOTE_SECONDARY_ALPHA)
    val secondary = if (replyTextContrast(muted, container) >= WCAG_AA_NORMAL_TEXT_CONTRAST) muted else content
    return ReplyQuoteColors(container = container, secondary = secondary)
}

/** Compares the final composited text and opaque surface using the existing appearance contrast rule. */
private fun replyTextContrast(
    text: Color,
    background: Color,
): Double =
    contrastRatio(
        text.compositeOver(background).toArgb().toLong() and 0xFFFFFFFFL,
        background.toArgb().toLong() and 0xFFFFFFFFL,
    )
