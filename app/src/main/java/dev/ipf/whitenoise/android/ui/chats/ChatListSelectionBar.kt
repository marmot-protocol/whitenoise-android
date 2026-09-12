package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAnchoredMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Selection title and Close remain separate from the prototype's pinned bulk controls. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListSelectionBar(onClose: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.chat_selection_title)) },
        navigationIcon = {
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
        },
    )
}

/** Prototype bottom controls expose every existing native action with the caller's actual scope and eligibility. */
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
) {
    var opening by remember { mutableStateOf<Any?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }

    fun canPerform(): Boolean = active && actionsEnabled && isCurrent()

    fun perform(action: () -> Unit) {
        if (canPerform()) {
            opening = null
            action()
        }
    }
    val description = pluralStringResource(R.plurals.chat_list_selected_count, count, count)
    // Intrinsic-width controls wrap as whole elements instead of splitting words in equal columns.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related)
            .testTag("chats.selectionControls"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            FilledTonalIconButton(onClick = { if (canPerform()) opening = Any() }, enabled = actionsEnabled) {
                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.actions))
            }
            val token = opening
            key(token) {
                WhiteNoiseAnchoredMenu(
                    expanded = token != null,
                    onDismissRequest = { if (opening === token) opening = null },
                    canRunAction = { token != null && opening === token && canPerform() },
                    items =
                        buildList {
                            if (showMarkRead) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(R.string.chat_row_action_mark_read),
                                        { perform(onMarkRead) },
                                    ),
                                )
                            }
                            if (showMarkUnread) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(R.string.chat_row_action_mark_unread),
                                        { perform(onMarkUnread) },
                                    ),
                                )
                            }
                            add(
                                WhiteNoiseMenuItem(
                                    stringResource(
                                        if (archiveAction == ChatListBulkArchiveAction.Archive) {
                                            R.string.archive
                                        } else {
                                            R.string.unarchive
                                        },
                                    ),
                                    { perform(onArchive) },
                                ),
                            )
                            add(
                                WhiteNoiseMenuItem(
                                    stringResource(R.string.chat_list_action_add_to_folder),
                                    { perform(onAddToFolder) },
                                ),
                            )
                            // Native single-chat pin/mute/order capabilities remain explicit named entries.
                            if (showPinToggle) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(
                                            if (pinned) {
                                                R.string.chat_row_action_unpin
                                            } else {
                                                R.string.chat_row_action_pin
                                            },
                                        ),
                                        { perform(onPinToggle) },
                                    ),
                                )
                            }
                            if (showMovePinnedUp) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(R.string.chat_row_action_move_up),
                                        { perform { onMovePinned(-1) } },
                                    ),
                                )
                            }
                            if (showMovePinnedDown) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(R.string.chat_row_action_move_down),
                                        { perform { onMovePinned(1) } },
                                    ),
                                )
                            }
                            if (showMuteToggle) {
                                add(
                                    WhiteNoiseMenuItem(
                                        stringResource(
                                            if (muted) {
                                                R.string.chat_row_action_unmute
                                            } else {
                                                R.string.chat_row_action_mute
                                            },
                                        ),
                                        { perform(onMuteToggle) },
                                    ),
                                )
                            }
                            add(
                                WhiteNoiseMenuItem(
                                    stringResource(R.string.delete),
                                    { perform(onDelete) },
                                    destructive = true,
                                ),
                            )
                        }.map { it.copy(enabled = canPerform()) },
                )
            }
        }
        Surface(
            Modifier.testTag("chats.selectionCount").semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
            shape = CircleShape,
            border = amoledOutlineBorder(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                description,
                Modifier.padding(WhiteNoiseSpacing.Related),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            FilledTonalButton(
                onClick = { perform(if (allVisibleSelected) onDeselectAll else onSelectAll) },
                enabled = actionsEnabled,
                border = amoledOutlineBorder(),
            ) {
                Text(
                    stringResource(
                        if (allVisibleSelected) R.string.chat_list_deselect_all else R.string.chat_list_select_all,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
