package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.outgoingIndicator

@Composable
internal fun OutgoingMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
) {
    OutgoingIndicatorIcon(status.outgoingIndicator() ?: return, tint)
}

@Suppress("FunctionNaming")
@Composable
internal fun OutgoingIndicatorIcon(
    indicator: OutgoingMessageIndicator,
    tint: Color,
) {
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
