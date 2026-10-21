package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio

private const val REPLY_QUOTE_FILL_ALPHA = 0.16f
private const val REPLY_QUOTE_SECONDARY_ALPHA = 0.78f
private const val REPLY_QUOTE_WARNING_ALPHA = 0.75f
private const val REPLY_QUOTE_STRONG_TINT_ALPHA = 0.32f

// Decorative inset separation; text independently retains the existing WCAG AA contrast floor.
private const val MIN_REPLY_INSET_CONTRAST = 1.2

private const val UNSIGNED_ARGB_MASK = 0xFFFFFFFFL

/** Container and readable text roles shared by the message reply-card boundaries. */
internal data class ReplyQuoteColors(
    val container: Color,
    val secondary: Color,
    val warning: Color? = null,
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
 * Prefer a darker inset with visible separation. Near-black and text-unreadable shades use a tint.
 * Check body and warning alpha after composition; AMOLED and semantic colors remain caller-owned.
 */
private fun customReplyQuoteColors(
    background: Color,
    content: Color,
): ReplyQuoteColors {
    val darker = Color.Black.copy(alpha = REPLY_QUOTE_FILL_ALPHA).compositeOver(background)
    val lighter = Color.White.copy(alpha = REPLY_QUOTE_FILL_ALPHA).compositeOver(background)
    val container =
        when {
            replyTextContrast(darker, background) >= MIN_REPLY_INSET_CONTRAST &&
                replyTextContrast(content, darker) >= WCAG_AA_NORMAL_TEXT_CONTRAST ->
                darker
            replyTextContrast(lighter, background) >= MIN_REPLY_INSET_CONTRAST -> lighter
            else -> Color.White.copy(alpha = REPLY_QUOTE_STRONG_TINT_ALPHA).compositeOver(background)
        }
    return ReplyQuoteColors(
        container = container,
        secondary = readableReplyText(content, container, REPLY_QUOTE_SECONDARY_ALPHA),
        warning = readableReplyText(content, container, REPLY_QUOTE_WARNING_ALPHA),
    )
}

/** Retains muted text when readable, falling back to the bubble's validated opaque content role. */
private fun readableReplyText(
    content: Color,
    background: Color,
    alpha: Float,
): Color {
    val muted = content.copy(alpha = alpha)
    return if (replyTextContrast(muted, background) >= WCAG_AA_NORMAL_TEXT_CONTRAST) muted else content
}

/** Compares the final composited text and opaque surface using the existing appearance contrast rule. */
private fun replyTextContrast(
    text: Color,
    background: Color,
): Double =
    contrastRatio(
        text.compositeOver(background).toArgb().toLong() and UNSIGNED_ARGB_MASK,
        background.toArgb().toLong() and UNSIGNED_ARGB_MASK,
    )
