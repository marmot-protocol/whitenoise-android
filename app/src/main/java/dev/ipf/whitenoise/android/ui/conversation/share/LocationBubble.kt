package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationEmbedActionIconSize
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationEmbedCard
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationEmbedIconDisc
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationEmbedPadding
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics

/**
 * Location bubble rendered from the shared coordinates with **no network
 * access** — deliberately. Auto-fetching map tiles on message receipt would
 * leak the recipient's IP (and reveal that they viewed the message) to a
 * third-party tile server before any interaction, which is unacceptable in a
 * private messenger. So the bubble shows the coordinates and opens the map in
 * the user's chosen maps app only on an explicit tap.
 */
@Composable
internal fun LocationMessageBubble(
    location: SharedLocation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConversationEmbedCard(
        modifier = modifier.width(ConversationMessageMetrics.RichContentCanvasWidth),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.padding(ConversationEmbedPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationEmbedIconDisc(painterResource(R.drawable.ic_location_on))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.share_location_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.location_open_in_maps),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ConversationEmbedActionIconSize),
            )
        }
    }
}
