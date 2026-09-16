package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.WCAG_NON_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.outgoingIndicator

// A delivered disc is a filled shape beside an 11 sp timestamp, so at the footer's full colour it
// outweighs the text it annotates. Sending keeps that colour — a 1.5 dp ring is already light — and
// a failure keeps the error colour, so only the settled success recedes to secondary metadata.
private const val SENT_DISC_TARGET_ALPHA = 0.6f
private const val SENT_DISC_ALPHA_STEP = 0.05f
private const val OPAQUE_ARGB_MASK = 0xFFFFFFFFL

/**
 * Fill for the settled delivery disc. A filled 14dp disc next to 11 sp text reads louder than the
 * timestamp it annotates, so the disc recedes toward the bubble — but only as far as the non-text
 * contrast floor allows, and no further. How far that is depends on the fill: an account's custom
 * bubble colour, the light-on-dark own bubble and AMOLED's white accent all afford different room,
 * so the softening is searched per fill rather than fixed, the way the footer label colour is.
 */
internal fun sentDeliveryDiscColor(
    tint: Color,
    containerColor: Color,
): Color {
    var alpha = SENT_DISC_TARGET_ALPHA
    while (alpha < 1f) {
        val candidate = tint.copy(alpha = tint.alpha * alpha)
        val composited = candidate.compositeOver(containerColor).opaqueArgb()
        if (contrastRatio(composited, containerColor.opaqueArgb()) >= WCAG_NON_TEXT_CONTRAST) return candidate
        alpha += SENT_DISC_ALPHA_STEP
    }
    return tint
}

/** Opaque ARGB value of a colour, for the shared contrast helper. */
private fun Color.opaqueArgb(): Long = toArgb().toLong() and OPAQUE_ARGB_MASK

/** Delivery glyph for an outgoing status in the prototype's ring, disc and warning forms. */
@Composable
internal fun OutgoingMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
    containerColor: Color? = null,
) {
    OutgoingIndicatorIcon(status.outgoingIndicator() ?: return, tint, containerColor)
}

/** The prototype's delivery glyph; the check takes the bubble colour, or the page surface outside a bubble. */
@Suppress("FunctionNaming")
@Composable
internal fun OutgoingIndicatorIcon(
    indicator: OutgoingMessageIndicator,
    tint: Color,
    containerColor: Color? = null,
) {
    BubbleDeliveryGlyph(indicator, tint, containerColor ?: MaterialTheme.colorScheme.surface)
}

/**
 * The prototype's in-bubble delivery glyphs: a 14dp progress ring while sending, a filled 14dp disc carrying a
 * 10dp check in the bubble's own colour once sent, and the warning glyph on failure.
 */
@Suppress("FunctionNaming")
@Composable
private fun BubbleDeliveryGlyph(
    indicator: OutgoingMessageIndicator,
    tint: Color,
    containerColor: Color,
) {
    when (indicator) {
        OutgoingMessageIndicator.Sending -> {
            val sending = stringResource(R.string.sending)
            // A status glyph, not a progress control: it must not read as the composer's progress indicator.
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).clearAndSetSemantics { contentDescription = sending },
                color = tint,
                strokeWidth = 1.5.dp,
            )
        }
        OutgoingMessageIndicator.Sent -> {
            val sent = stringResource(R.string.sent)
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(sentDeliveryDiscColor(tint, containerColor))
                        .semantics { contentDescription = sent },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = containerColor,
                )
            }
        }
        OutgoingMessageIndicator.Failed ->
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = stringResource(R.string.send_failed),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
    }
}
