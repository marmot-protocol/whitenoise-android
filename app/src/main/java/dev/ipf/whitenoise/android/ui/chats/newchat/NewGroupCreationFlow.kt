package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Member references and setup UI belong to one active account/runtime, independent of the parent route. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun NewGroupCreationFlow(
    appState: WhiteNoiseAppState,
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onClose: () -> Unit,
    onCreateSubmitted: () -> Long,
    onCreateFlowSuperseded: () -> Unit,
    initialMembers: List<RecipientSearch.Candidate>,
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    key(appState.activeAccountRef, appState.runtimeGeneration) {
        NewGroupAccountFlow(
            appState,
            onCreateCompletedOpen,
            onClose,
            onCreateSubmitted,
            onCreateFlowSuperseded,
            initialMembers,
        )
    }
}

/** A temporary setup Back retains the authored draft and selection; permanent departure consumes callbacks. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun NewGroupAccountFlow(
    appState: WhiteNoiseAppState,
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onClose: () -> Unit,
    onCreateSubmitted: () -> Long,
    onCreateFlowSuperseded: () -> Unit,
    initialMembers: List<RecipientSearch.Candidate>,
) {
    val account = appState.activeAccountRef
    val runtime = remember { appState.runtimeGeneration }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }

    fun current() =
        active &&
            account != null &&
            appState.activeAccountRef == account &&
            appState.runtimeGeneration == runtime &&
            !appState.signOutInProgress &&
            !appState.wipeInProgress
    val selected =
        rememberSaveable(saver = GroupMemberSelectionSaver) {
            mutableStateListOf<RecipientSearch.Candidate>().apply { addAll(initialMembers) }
        }
    val draft = rememberNewGroupDraft()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    if (setupOpen) {
        NewGroupSetupScreen(
            appState = appState,
            members = selected,
            draft = draft,
            onBack = {
                if (current()) {
                    onCreateFlowSuperseded()
                    if (draft.retryGroupIdHex != null) {
                        active = false
                        onClose()
                    } else {
                        setupOpen = false
                    }
                }
            },
            onCreateCompletedOpen = { item, token ->
                if (current()) {
                    active = false
                    onCreateCompletedOpen(item, token)
                }
            },
            onCreateSubmitted = onCreateSubmitted,
        )
    } else {
        NewGroupRecipientPickerScreen(
            appState = appState,
            selected = selected,
            onBack = {
                if (current()) {
                    active = false
                    onClose()
                }
            },
            onConfirm = { if (current()) setupOpen = true },
        )
    }
}

/** Saves chosen public references and display fallbacks only; native discovery metadata is not persisted. */
private val GroupMemberSelectionSaver =
    listSaver<SnapshotStateList<RecipientSearch.Candidate>, String>(
        save = { members -> members.flatMap { listOf(it.accountIdHex, it.displayName, it.npub) } },
        restore = { values ->
            mutableStateListOf<RecipientSearch.Candidate>().apply {
                values.chunked(3).filter { it.size == 3 }.forEach {
                    add(RecipientSearch.Candidate(it[0], it[1], it[2]))
                }
            }
        },
    )
