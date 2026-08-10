package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun MessageSelectionBottomBar(
    availability: BatchSelectionActionAvailability,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val actionSlotPx = with(density) { 48.dp.roundToPx() }
    val offered = remember(availability) { offeredMessageSelectionBarActions(availability) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            val barWidthPx = with(density) { maxWidth.roundToPx() }
            val layout =
                remember(barWidthPx, offered) {
                    partitionMessageSelectionBarActionsForWidth(
                        offered = offered,
                        barWidthPx = barWidthPx,
                        actionSlotWidthPx = actionSlotPx,
                        deleteSlotWidthPx = actionSlotPx,
                        overflowSlotWidthPx = actionSlotPx,
                    )
                }
            var overflowOpen by remember(layout.overflow) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    layout.inline.forEach { action ->
                        SelectionActionIconButton(
                            action = action,
                            availability = availability,
                            onCopy = onCopy,
                            onForward = onForward,
                            onSave = onSave,
                            onReply = onReply,
                            onInfo = onInfo,
                        )
                    }
                    if (layout.overflow.isNotEmpty()) {
                        SelectionOverflowMenu(
                            overflow = layout.overflow,
                            availability = availability,
                            overflowOpen = overflowOpen,
                            onOverflowOpenChange = { overflowOpen = it },
                            onCopy = onCopy,
                            onForward = onForward,
                            onSave = onSave,
                            onReply = onReply,
                            onInfo = onInfo,
                        )
                    }
                }
                val deleteLabel = stringResource(R.string.message_selection_action_delete)
                SelectionTooltipIconButton(
                    label = deleteLabel,
                    onClick = onDelete,
                    enabled = availability.canDelete,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = deleteLabel,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
private fun SelectionOverflowMenu(
    overflow: List<MessageSelectionBarAction>,
    availability: BatchSelectionActionAvailability,
    overflowOpen: Boolean,
    onOverflowOpenChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
) {
    val actionsLabel = stringResource(R.string.actions)
    SelectionTooltipIconButton(
        label = actionsLabel,
        onClick = { onOverflowOpenChange(true) },
    ) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = actionsLabel,
        )
    }
    DropdownMenu(
        expanded = overflowOpen,
        onDismissRequest = { onOverflowOpenChange(false) },
        shape = MenuDefaults.shape,
        border = amoledSurfaceBorderStroke(),
    ) {
        overflow.forEach { action ->
            DropdownMenuItem(
                text = { Text(selectionActionLabel(action)) },
                onClick = {
                    onOverflowOpenChange(false)
                    dispatchSelectionAction(
                        action = action,
                        availability = availability,
                        onCopy = onCopy,
                        onForward = onForward,
                        onSave = onSave,
                        onReply = onReply,
                        onInfo = onInfo,
                    )
                },
                enabled = selectionActionEnabled(action, availability),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SelectionActionIconButton(
    action: MessageSelectionBarAction,
    availability: BatchSelectionActionAvailability,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
) {
    val label = selectionActionLabel(action)
    SelectionTooltipIconButton(
        label = label,
        onClick = {
            dispatchSelectionAction(
                action = action,
                availability = availability,
                onCopy = onCopy,
                onForward = onForward,
                onSave = onSave,
                onReply = onReply,
                onInfo = onInfo,
            )
        },
        enabled = selectionActionEnabled(action, availability),
    ) {
        Icon(selectionActionIcon(action), contentDescription = label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
private fun SelectionTooltipIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    icon: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = colors,
        ) {
            icon()
        }
    }
}

@Composable
private fun selectionActionLabel(action: MessageSelectionBarAction): String =
    when (action) {
        MessageSelectionBarAction.Reply -> stringResource(R.string.reply)
        MessageSelectionBarAction.Info -> stringResource(R.string.info)
        MessageSelectionBarAction.Copy -> stringResource(R.string.copy)
        MessageSelectionBarAction.Forward -> stringResource(R.string.forward)
        MessageSelectionBarAction.Save -> stringResource(R.string.shared_media_save)
    }

private fun selectionActionIcon(action: MessageSelectionBarAction) =
    when (action) {
        MessageSelectionBarAction.Reply -> Icons.AutoMirrored.Filled.Reply
        MessageSelectionBarAction.Info -> Icons.Default.Info
        MessageSelectionBarAction.Copy -> Icons.Default.ContentCopy
        MessageSelectionBarAction.Forward -> Icons.AutoMirrored.Filled.Forward
        MessageSelectionBarAction.Save -> Icons.Default.Download
    }

private fun selectionActionEnabled(
    action: MessageSelectionBarAction,
    availability: BatchSelectionActionAvailability,
): Boolean =
    when (action) {
        MessageSelectionBarAction.Reply -> availability.canReply
        MessageSelectionBarAction.Info -> availability.canInfo
        MessageSelectionBarAction.Copy -> availability.canCopy
        MessageSelectionBarAction.Forward -> availability.canForward
        MessageSelectionBarAction.Save -> availability.canSave
    }

private fun dispatchSelectionAction(
    action: MessageSelectionBarAction,
    availability: BatchSelectionActionAvailability,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onReply: () -> Unit,
    onInfo: () -> Unit,
) {
    if (!selectionActionEnabled(action, availability)) return
    when (action) {
        MessageSelectionBarAction.Reply -> onReply()
        MessageSelectionBarAction.Info -> onInfo()
        MessageSelectionBarAction.Copy -> onCopy()
        MessageSelectionBarAction.Forward -> onForward()
        MessageSelectionBarAction.Save -> onSave()
    }
}
