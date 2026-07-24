package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/**
 * Folder assignment sheet for one or many chats: every custom folder as a
 * tri-state checkbox (on = every target chat is a manual member, mixed =
 * some are), plus a New-folder entry that hands off to the create form with
 * the targets preselected. Toggling edits MANUAL membership only — that is
 * the one thing this surface controls; rule-matched chats keep following
 * their rule regardless of what is toggled here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun ChatFolderPickerSheet(
    appState: WhiteNoiseAppState,
    targetChatIds: List<String>,
    onCreateFolder: () -> Unit,
    onDismiss: () -> Unit,
    ruleMatchedFolderIds: Set<String> = emptySet(),
) {
    val accountRef = appState.activeAccountRef
    val store = appState.chatFolderPreferences
    val storeState by store.state.collectAsState()
    val customFolders =
        remember(storeState, accountRef) {
            accountRef?.let(store::foldersFor).orEmpty().filterNot { it.isSystem }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.chat_list_action_add_to_folder),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            customFolders.forEach { folder ->
                val membership =
                    accountRef?.let { store.membershipFor(it, folder.id) }.orEmpty()
                val checkboxState = chatFolderTriState(targetChatIds, membership)
                ListItem(
                    modifier =
                        Modifier.clickable {
                            val include = checkboxState != ToggleableState.On
                            accountRef?.let { account ->
                                targetChatIds.forEach { store.setChatInFolder(account, folder.id, it, include) }
                            }
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { TriStateCheckbox(state = checkboxState, onClick = null) },
                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent =
                        if (folder.id in ruleMatchedFolderIds) {
                            // The checkbox reflects manual membership only; a
                            // rule keeps this chat in the folder regardless.
                            { Text(stringResource(R.string.chat_folder_included_by_rule)) }
                        } else {
                            null
                        },
                )
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onCreateFolder),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.chat_folder_new)) },
            )
        }
    }
}

/** On when every target is a manual member, mixed when only some are. */
internal fun chatFolderTriState(
    targetChatIds: Collection<String>,
    membership: Set<String>,
): ToggleableState =
    when {
        targetChatIds.isNotEmpty() && targetChatIds.all { it in membership } -> ToggleableState.On
        targetChatIds.any { it in membership } -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
