package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.settings.chatFolderDisplayName
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Target dialog chrome retains production multi-folder/manual membership and the richer native New folder route. */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
internal fun ChatFolderPickerSheet(
    appState: WhiteNoiseAppState,
    targetChatIds: List<String>,
    onCreateFolder: () -> Unit,
    onDismiss: () -> Unit,
    ruleMatchedFolderIds: Set<String> = emptySet(),
) {
    val account = remember(appState, targetChatIds) { appState.activeAccountRef }
    val runtime = remember(appState, targetChatIds) { appState.runtimeGeneration }
    val store = appState.chatFolderPreferences
    val draft =
        rememberSaveable(appState, targetChatIds, saver = ChatFolderAssignmentDraft.saver) {
            ChatFolderAssignmentDraft(account.orEmpty(), runtime, targetChatIds)
        }
    val session =
        remember(appState, targetChatIds, draft) {
            ChatFolderAssignmentSession(account.orEmpty(), targetChatIds, store, draft, runtime) {
                val sameOwner =
                    account != null && appState.activeAccountRef == account && appState.runtimeGeneration == runtime
                val available =
                    !appState.signOutInProgress &&
                        !appState.wipeInProgress &&
                        appState.retainedAccountReactivationRef == null
                sameOwner && available
            }
        }
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(session) { onDispose { session.dispose() } }
    LaunchedEffect(
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.signOutInProgress,
        appState.wipeInProgress,
        appState.retainedAccountReactivationRef,
    ) {
        if (!session.isCurrent()) dismiss()
    }
    if (!session.isCurrent()) return
    val storeState by store.state.collectAsState()
    val folders = remember(storeState, account) { account?.let(store::foldersFor).orEmpty() }
    var failed by remember(session) { mutableStateOf(false) }
    WhiteNoiseAlertDialog(
        onDismissRequest = { session.leave { dismiss() } },
        title = { Text(stringResource(R.string.chat_list_action_add_to_folder)) },
        text = {
            Column(
                Modifier.heightIn(max = FolderPickerMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                if (failed) Text(stringResource(R.string.folder_save_failed), color = MaterialTheme.colorScheme.error)
                folders.forEach { folder ->
                    val membership = account?.let { store.membershipFor(it, folder.id) }.orEmpty()
                    val state =
                        session.intents[folder.id]?.let { if (it) ToggleableState.On else ToggleableState.Off }
                            ?: chatFolderTriState(draft.targets, membership)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .triStateToggleable(state, role = Role.Checkbox) {
                                session.choose(folder.id, state != ToggleableState.On)
                            }.padding(vertical = WhiteNoiseSpacing.Related)
                            .testTag("chat.folderChoice.${folder.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(FolderPickerIconSize))
                        Spacer(Modifier.width(WhiteNoiseSpacing.FormField))
                        Column(Modifier.weight(1f)) {
                            Text(chatFolderDisplayName(folder))
                            if (folder.id in ruleMatchedFolderIds) {
                                Text(
                                    stringResource(R.string.chat_folder_included_by_rule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TriStateCheckbox(state, onClick = null)
                    }
                }
                TextButton(
                    onClick = { session.leave(onCreateFolder) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("chat.folderCreate"),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(FolderPickerIconSize))
                    Spacer(Modifier.width(WhiteNoiseSpacing.FormField))
                    Text(stringResource(R.string.chat_folder_new), Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (session.isCurrent()) {
                    val saved =
                        try {
                            session.save()
                        } catch (_: Exception) {
                            false
                        }
                    if (saved) dismiss() else failed = true
                }
            }, enabled = session.intents.isNotEmpty(), modifier = Modifier.testTag("chat.folderSave")) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { session.leave { dismiss() } }, modifier = Modifier.testTag("chat.folderCancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** On when every target is a manual member, mixed when only some are; rules never alter the checkbox. */
internal fun chatFolderTriState(
    targetChatIds: Collection<String>,
    membership: Set<String>,
): ToggleableState =
    when {
        targetChatIds.isNotEmpty() && targetChatIds.all { it in membership } -> ToggleableState.On
        targetChatIds.any { it in membership } -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

private val FolderPickerMaxHeight = 420.dp
private val FolderPickerIconSize = 24.dp
