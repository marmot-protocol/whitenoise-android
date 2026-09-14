package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.outgoingIndicator

@Composable
internal fun OutgoingMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
    containerColor: Color? = null,
) {
    OutgoingIndicatorIcon(status.outgoingIndicator() ?: return, tint, containerColor)
}

@Suppress("FunctionNaming")
@Composable
internal fun OutgoingIndicatorIcon(
    indicator: OutgoingMessageIndicator,
    tint: Color,
    containerColor: Color? = null,
) {
    if (containerColor != null) {
        BubbleDeliveryGlyph(indicator, tint, containerColor)
        return
    }
    when (indicator) {
        OutgoingMessageIndicator.Sending ->
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.sending),
                modifier = Modifier.size(14.dp),
                tint = tint.copy(alpha = 0.76f),
            )
        OutgoingMessageIndicator.Sent ->
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.sent),
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        OutgoingMessageIndicator.Failed ->
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.send_failed),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
    }
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
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp).semantics { contentDescription = sending },
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
                        .background(tint)
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
