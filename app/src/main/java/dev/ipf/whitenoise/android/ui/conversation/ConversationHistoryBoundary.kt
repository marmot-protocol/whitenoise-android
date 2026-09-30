package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel

/** Only a settled, exhausted, nonempty retained history can show the room policy at its oldest edge. */
internal fun retentionHistoryBoundaryVisible(
    retentionSeconds: ULong,
    hasMessages: Boolean,
    initialLoadStarted: Boolean,
    hasMoreBefore: Boolean,
    isLoading: Boolean,
    isLoadingPage: Boolean,
    isLoadingOlder: Boolean,
    olderLoadFailed: Boolean,
): Boolean =
    retentionSeconds > 0uL &&
        hasMessages &&
        initialLoadStarted &&
        !hasMoreBefore &&
        !isLoading &&
        !isLoadingPage &&
        !isLoadingOlder &&
        !olderLoadFailed

/** A single room-level hint; it does not claim that any particular message expired. */
@Composable
@Suppress("FunctionNaming") // Compose UI entry point.
internal fun ConversationHistoryBoundary(
    retentionSeconds: ULong,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.conversation_history_may_have_expired)
    val duration = disappearingMessagesLabel(retentionSeconds.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
    val timerDescription = stringResource(R.string.conversation_history_retention_description, title, duration)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = timerDescription }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Icon(
                painter = painterResource(R.drawable.ic_timer),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                color = color,
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
