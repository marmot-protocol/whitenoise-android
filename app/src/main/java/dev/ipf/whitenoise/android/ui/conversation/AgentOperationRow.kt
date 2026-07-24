@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AgentOperationPresentation
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.formatAgentOperationArguments
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDeleteDialog

private val FAILURE_STATUSES = setOf("failed", "error", "cancelled", "canceled")
private val SUCCESS_STATUSES = setOf("completed", "complete", "finished", "success", "succeeded", "done")
private const val AGENT_OPERATION_ROW_WIDTH_FRACTION = 0.95f

internal fun shouldRenderDedicatedAgentOperationRow(
    projectedDeleted: Boolean,
    optimisticallyDeleted: Boolean,
    invalidated: Boolean,
): Boolean = !projectedDeleted && !optimisticallyDeleted && !invalidated

internal data class AgentOperationSenderPresentation(
    val name: String,
    val seed: String,
    val avatarUrl: String?,
)

@Composable
internal fun AgentOperationTimelineRow(
    item: TimelineMessage,
    operation: AgentOperationPresentation,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    readOnly: Boolean = false,
) {
    val record = item.record
    val mine = controller.isMessageMine(record)
    val senderName = appState.displayName(record.sender)
    val showSender = GroupProjector.shouldShowTranscriptSenderAvatar(controller.memberCount, mine)
    val deleteCapability =
        if (readOnly) {
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = false)
        } else {
            controller.deleteCapabilityFor(record)
        }
    var deleteDialogOpen by rememberSaveable(record.messageIdHex) { mutableStateOf(false) }

    AgentOperationRow(
        messageId = record.messageIdHex,
        operation = operation,
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
        AgentOperationDeleteDialog(
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

@Composable
private fun AgentOperationDeleteDialog(
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    capability: MessageDeleteCapability,
    mine: Boolean,
    senderDisplayName: String,
    onDismiss: () -> Unit,
) {
    var deleteForEveryoneInFlight by remember(record.messageIdHex) { mutableStateOf(false) }
    MessageDeleteDialog(
        capability = capability,
        mine = mine,
        senderDisplayName = senderDisplayName,
        deleteInFlight = deleteForEveryoneInFlight,
        onDeleteForEveryone = {
            if (!deleteForEveryoneInFlight) {
                deleteForEveryoneInFlight = true
                appState.launchMutation {
                    try {
                        if (controller.deleteMessage(record)) onDismiss()
                    } finally {
                        deleteForEveryoneInFlight = false
                    }
                }
            }
        },
        onDeleteForMe = {
            onDismiss()
            controller.hideMessageForMe(record.messageIdHex)
        },
        onDismissRequest = onDismiss,
    )
}

@Composable
internal fun AgentOperationRow(
    messageId: String,
    operation: AgentOperationPresentation,
    mine: Boolean = false,
    sender: AgentOperationSenderPresentation? = null,
    onSenderClick: (() -> Unit)? = null,
    onRequestDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(AGENT_OPERATION_ROW_WIDTH_FRACTION),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (sender != null) {
                Box(
                    modifier =
                        if (onSenderClick != null) {
                            Modifier
                                .clip(CircleShape)
                                .clickable(onClick = onSenderClick)
                        } else {
                            Modifier
                        },
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
            Column(modifier = Modifier.weight(1f)) {
                if (sender != null) {
                    Text(
                        text = sender.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AgentOperationChip(
                    operation = operation,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    onRequestDelete = onRequestDelete,
                    modifier = modifier,
                )
            }
        }
    }
}

/** Compact tool-call chip with full wire details available on demand. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentOperationChip(
    operation: AgentOperationPresentation,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRequestDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val statusColor = agentOperationStatusColor(operation)
    val toggleLabel =
        stringResource(
            if (expanded) {
                R.string.group_system_hide_details
            } else {
                R.string.group_system_show_details
            },
        )
    val messageActionsLabel = stringResource(R.string.message_actions)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (operation.canExpand || onRequestDelete != null) {
                        Modifier.combinedClickable(
                            onClickLabel = toggleLabel.takeIf { operation.canExpand },
                            onLongClickLabel = messageActionsLabel.takeIf { onRequestDelete != null },
                            role = Role.Button,
                            onClick = { if (operation.canExpand) onToggle() },
                            onLongClick = onRequestDelete,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentOperationHeader(
                operation = operation,
                expanded = expanded,
                statusColor = statusColor,
            )
            Text(
                text = operation.collapsedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                AgentOperationExpandedDetails(operation)
            }
        }
    }
}

@Composable
private fun AgentOperationHeader(
    operation: AgentOperationPresentation,
    expanded: Boolean,
    statusColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "⚙",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            text = operation.name ?: operation.eventType.orEmpty(),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AgentOperationStatus(operation = operation, color = statusColor)
        if (operation.canExpand) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AgentOperationExpandedDetails(operation: AgentOperationPresentation) {
    AppDivider()
    operation.text.takeIf(String::isNotBlank)?.let { text ->
        AgentOperationDetail(
            label = stringResource(R.string.message),
            value = text,
        )
    }
    operation.preview?.let { preview ->
        AgentOperationDetail(
            label = stringResource(R.string.preview),
            value = preview,
        )
    }
    operation.argumentsJson?.let { arguments ->
        AgentOperationDetail(
            label = stringResource(R.string.details),
            value = remember(arguments) { formatAgentOperationArguments(arguments) },
        )
    }
    completionSummary(operation)?.let { summary ->
        AgentOperationDetail(
            label = stringResource(R.string.status),
            value = summary,
        )
    }
}

@Composable
private fun AgentOperationStatus(
    operation: AgentOperationPresentation,
    color: Color,
) {
    val label = operation.status ?: operation.ok?.let { if (it) "✓" else "✕" }
    if (label != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "●", color = color, style = MaterialTheme.typography.labelSmall)
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentOperationDetail(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun agentOperationStatusColor(operation: AgentOperationPresentation): Color {
    val status = operation.status?.lowercase()
    return when {
        operation.ok == false -> MaterialTheme.colorScheme.error
        operation.ok == true -> MaterialTheme.colorScheme.primary
        status in FAILURE_STATUSES -> MaterialTheme.colorScheme.error
        status in SUCCESS_STATUSES -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun completionSummary(operation: AgentOperationPresentation): String? =
    buildList {
        operation.status?.let(::add)
        operation.ok?.let { add(if (it) "✓" else "✕") }
        operation.durationMs?.let { add("$it ms") }
    }.joinToString(" · ").takeIf(String::isNotEmpty)
