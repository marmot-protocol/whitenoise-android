package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.common.selectionRowIcon

internal const val CHAT_ROW_SELECTION_INDICATOR_TAG = "chat-row-selection-indicator"

@Suppress("FunctionNaming")
@Composable
internal fun ChatRowLayout(
    title: String,
    timestampAt: ULong,
    rowHasUnread: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    leadingContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    supportingMetadata: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        leadingContent = leadingContent,
        headlineContent = {
            ChatRowTitleLine(
                title = title,
                timestampAt = timestampAt,
                rowHasUnread = rowHasUnread,
                showTimestamp = !selectionMode,
            )
        },
        supportingContent = {
            ChatRowSupportingLine(
                supportingContent = supportingContent,
                supportingMetadata = supportingMetadata.takeUnless { selectionMode },
            )
        },
        trailingContent =
            if (selectionMode) {
                {
                    ChatRowSelectionIndicator(selected = selected)
                }
            } else {
                null
            },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun ChatRowTitleLine(
    title: String,
    timestampAt: ULong,
    rowHasUnread: Boolean,
    showTimestamp: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        if (showTimestamp) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = rememberedRelativeTime(timestampAt),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (rowHasUnread) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ChatRowSupportingLine(
    supportingContent: @Composable () -> Unit,
    supportingMetadata: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            supportingContent()
        }
        if (supportingMetadata != null) {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                supportingMetadata()
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun ChatRowSupportingMetadata(
    pendingConfirmation: Boolean,
    rowHasUnread: Boolean,
    rowUnreadCount: ULong,
    unreadMention: Boolean,
    actionColors: AccountActionColors?,
    pinned: Boolean,
) {
    if (pendingConfirmation) {
        Badge { Text(stringResource(R.string.invited)) }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pinned) PinnedBadge()
            if (rowHasUnread) {
                if (unreadMention) MentionBadge()
                UnreadCountBadge(rowUnreadCount, actionColors = actionColors)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ChatRowSelectionIndicator(selected: Boolean) {
    Icon(
        imageVector = chatRowSelectionIcon(selected),
        // The clickable row already exposes selected semantics. Keeping the
        // visual indicator decorative avoids a second TalkBack announcement.
        contentDescription = null,
        tint =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            Modifier
                .size(24.dp)
                .testTag(CHAT_ROW_SELECTION_INDICATOR_TAG),
    )
}

internal fun chatRowSelectionIcon(selected: Boolean): ImageVector = selectionRowIcon(selected)
