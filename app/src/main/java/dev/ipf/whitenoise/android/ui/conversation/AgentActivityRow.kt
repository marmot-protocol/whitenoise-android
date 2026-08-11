@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AgentActivityPresentation
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar

private const val AGENT_ACTIVITY_ROW_WIDTH_FRACTION = 0.95f

@Composable
internal fun AgentActivityTimelineRow(
    item: TimelineMessage,
    activity: AgentActivityPresentation,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    readOnly: Boolean = false,
) {
    val record = item.record
    val mine = controller.isMessageMine(record)
    val senderName = appState.displayName(record.sender)
    val showSender =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            isDm = controller.isDm,
            mine = mine,
        )
    val deleteCapability =
        if (readOnly) {
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = false)
        } else {
            controller.deleteCapabilityFor(record)
        }
    var deleteDialogOpen by rememberSaveable(record.messageIdHex) { mutableStateOf(false) }
    AgentActivityRow(
        activity = activity,
        mine = mine,
        sender =
            if (showSender) {
                AgentOperationSenderPresentation(
                    name = senderName,
                    seed = record.sender,
                    avatarUrl = appState.avatarUrl(record.sender),
                )
            } else {
                null
            },
        onSenderClick = { appState.presentProfile(appState.npub(record.sender)) },
        onRequestDelete =
            if (deleteCapability.canDeleteAtAll) {
                { deleteDialogOpen = true }
            } else {
                null
            },
    )
    if (deleteDialogOpen) {
        DedicatedMessageDeleteDialog(
            record = record,
            controller = controller,
            appState = appState,
            capability = deleteCapability,
            mine = mine,
            senderDisplayName = senderName,
            onDismiss = { deleteDialogOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentActivityRow(
    activity: AgentActivityPresentation,
    mine: Boolean = false,
    sender: AgentOperationSenderPresentation? = null,
    onSenderClick: (() -> Unit)? = null,
    onRequestDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val messageActionsLabel = stringResource(R.string.message_actions)
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth(AGENT_ACTIVITY_ROW_WIDTH_FRACTION)
                    .agentActivityActions(messageActionsLabel, onRequestDelete),
            verticalAlignment = Alignment.Bottom,
        ) {
            sender?.let { AgentActivitySender(it, onSenderClick) }
            AgentActivityContent(activity = activity, senderName = sender?.name)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.agentActivityActions(
    actionLabel: String,
    onRequestDelete: (() -> Unit)?,
): Modifier =
    if (onRequestDelete == null) {
        this
    } else {
        combinedClickable(
            onClickLabel = actionLabel,
            onLongClickLabel = actionLabel,
            role = Role.Button,
            onClick = onRequestDelete,
            onLongClick = onRequestDelete,
        )
    }

@Composable
private fun AgentActivitySender(
    sender: AgentOperationSenderPresentation,
    onSenderClick: (() -> Unit)?,
) {
    val openProfileLabel = stringResource(R.string.chat_list_search_open_profile)
    Box(
        modifier =
            if (onSenderClick == null) {
                Modifier
            } else {
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = openProfileLabel,
                        role = Role.Button,
                        onClick = onSenderClick,
                    ).semantics { contentDescription = "$openProfileLabel: ${sender.name}" }
            },
        contentAlignment = Alignment.Center,
    ) {
        Avatar(
            title = sender.name,
            seed = sender.seed,
            size = 32.dp,
            pictureUrl = sender.avatarUrl,
        )
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun RowScope.AgentActivityContent(
    activity: AgentActivityPresentation,
    senderName: String?,
) {
    Column(modifier = Modifier.weight(1f)) {
        senderName?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = activity.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(start = 4.dp),
        )
        activity.status?.let { status ->
            Text(
                text = status.replace('_', ' ').replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
