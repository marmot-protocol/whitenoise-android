package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Selection title and Close remain separate from the prototype's pinned bulk controls. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListSelectionBar(onClose: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.chat_selection_title)) },
        navigationIcon = {
            IconButton(
                onClose,
                modifier = Modifier.testTag("chats.selection.close"),
            ) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
        },
    )
}

/**
 * Direct bulk controls retain native eligibility and reject callbacks from a replaced selection. A non-null
 * [abandonLabel] adds the separate destructive abandonment (#3310), kept apart from Leave and local delete.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ChatListSelectionControls(
    count: Int,
    archiveAction: ChatListBulkArchiveAction,
    actionsEnabled: Boolean,
    allVisibleSelected: Boolean,
    showMarkRead: Boolean,
    showMarkUnread: Boolean,
    showMuteToggle: Boolean,
    muted: Boolean,
    showPinToggle: Boolean,
    pinned: Boolean,
    showMovePinnedUp: Boolean,
    showMovePinnedDown: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAddToFolder: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMuteToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onMovePinned: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    isCurrent: () -> Boolean = { true },
    deleteLabel: String = stringResource(R.string.delete),
    abandonLabel: String? = null,
    onAbandon: () -> Unit = {},
    showMute: Boolean = showMuteToggle && !muted,
    showUnmute: Boolean = showMuteToggle && muted,
    showPin: Boolean = showPinToggle && !pinned,
    showUnpin: Boolean = showPinToggle && pinned,
    onSetMuted: ((Boolean) -> Unit)? = null,
    onSetPinned: ((Boolean) -> Unit)? = null,
    onAlternateArchive: (() -> Unit)? = null,
    onExcludeFromFolder: (() -> Unit)? = null,
    onDeleteLocal: (() -> Unit)? = null,
) {
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }

    /** An action runs only while the bar is active, enabled and still current. */
    fun canPerform(): Boolean = active && actionsEnabled && isCurrent()

    /** Runs [action] only while this selection bar still owns its callbacks. */
    fun perform(action: () -> Unit) {
        if (canPerform()) action()
    }
    val description = pluralStringResource(R.plurals.chat_list_selected_count, count, count)
    val selectAllLabel =
        stringResource(
            if (allVisibleSelected) R.string.chat_list_deselect_all else R.string.chat_list_select_all,
        )
    val archiveLabel =
        stringResource(
            if (archiveAction == ChatListBulkArchiveAction.Archive) R.string.archive else R.string.unarchive,
        )
    // Icon actions wrap as whole 48 dp targets at narrow widths.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related)
            .testTag("chats.selectionControls")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        DirectSelectionAction(
            label = selectAllLabel,
            icon = if (allVisibleSelected) R.drawable.ic_deselect_all else R.drawable.ic_select_all,
            enabled = actionsEnabled,
            modifier = Modifier.testTag("chats.selectAllAction"),
            onClick = { perform(if (allVisibleSelected) onDeselectAll else onSelectAll) },
        )
        DirectSelectionAction(
            label = archiveLabel,
            icon =
                if (archiveAction == ChatListBulkArchiveAction.Archive) {
                    R.drawable.ic_archive
                } else {
                    R.drawable.ic_unarchive
                },
            enabled = actionsEnabled,
            onClick = { perform(onArchive) },
        )
        DirectSelectionAction(
            label = stringResource(R.string.chat_list_action_add_to_folder),
            icon = R.drawable.ic_folder_add,
            enabled = actionsEnabled,
            onClick = { perform(onAddToFolder) },
        )
        if (showMarkRead) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_mark_read),
                R.drawable.ic_check,
                actionsEnabled,
            ) {
                perform(onMarkRead)
            }
        }
        if (showMarkUnread) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_mark_unread),
                R.drawable.ic_mark_unread,
                actionsEnabled,
            ) {
                perform(onMarkUnread)
            }
        }
        if (showMute) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_mute),
                R.drawable.ic_notifications_off,
                actionsEnabled,
            ) {
                perform { onSetMuted?.invoke(true) ?: onMuteToggle() }
            }
        }
        if (showUnmute) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_unmute),
                R.drawable.ic_settings_notifications,
                actionsEnabled,
            ) {
                perform { onSetMuted?.invoke(false) ?: onMuteToggle() }
            }
        }
        if (showPin) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_pin),
                R.drawable.ic_push_pin,
                actionsEnabled,
            ) {
                perform { onSetPinned?.invoke(true) ?: onPinToggle() }
            }
        }
        if (showUnpin) {
            DirectSelectionAction(stringResource(R.string.chat_row_action_unpin), R.drawable.ic_unpin, actionsEnabled) {
                perform { onSetPinned?.invoke(false) ?: onPinToggle() }
            }
        }
        onAlternateArchive?.let { alternate ->
            DirectSelectionAction(
                stringResource(
                    if (archiveAction == ChatListBulkArchiveAction.Archive) R.string.unarchive else R.string.archive,
                ),
                if (archiveAction == ChatListBulkArchiveAction.Archive) {
                    R.drawable.ic_unarchive
                } else {
                    R.drawable.ic_archive
                },
                actionsEnabled,
            ) { perform(alternate) }
        }
        onExcludeFromFolder?.let { exclude ->
            DirectSelectionAction(stringResource(R.string.folder_exclude_chat), R.drawable.ic_folder_remove, actionsEnabled) {
                perform(exclude)
            }
        }
        if (showMovePinnedUp) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_move_up),
                R.drawable.ic_arrow_up,
                actionsEnabled,
            ) {
                perform { onMovePinned(-1) }
            }
        }
        if (showMovePinnedDown) {
            DirectSelectionAction(
                stringResource(R.string.chat_row_action_move_down),
                R.drawable.ic_arrow_down,
                actionsEnabled,
            ) {
                perform { onMovePinned(1) }
            }
        }
        onDeleteLocal?.let { deleteLocal ->
            DirectSelectionAction(
                label = stringResource(R.string.delete_from_device),
                icon = R.drawable.ic_delete,
                enabled = actionsEnabled,
                destructive = true,
                onClick = { perform(deleteLocal) },
            )
        }
        DirectSelectionAction(
            label = deleteLabel,
            icon = if (deleteLabel == stringResource(R.string.leave_and_delete)) R.drawable.ic_logout else R.drawable.ic_delete,
            enabled = actionsEnabled,
            destructive = true,
            onClick = { perform(onDelete) },
        )
        abandonLabel?.let { label ->
            DirectSelectionAction(
                label = label,
                icon = R.drawable.ic_person_remove,
                enabled = actionsEnabled,
                destructive = true,
                onClick = { perform(onAbandon) },
            )
        }
    }
}

/** A localized tooltip and 48 dp target keep icon actions identifiable at compact widths. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun DirectSelectionAction(
    label: String,
    @DrawableRes icon: Int,
    enabled: Boolean,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        if (destructive) {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.size(48.dp),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) { Icon(painterResource(icon), contentDescription = label) }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
                Icon(painterResource(icon), contentDescription = label)
            }
        }
    }
}
