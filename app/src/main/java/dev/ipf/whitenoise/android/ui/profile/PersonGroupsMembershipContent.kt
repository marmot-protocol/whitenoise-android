package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.GroupAvatarAnimation
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import kotlinx.coroutines.awaitCancellation

/** Shares the existing controller's authorization rules, including self, DM and admin-target restrictions. */
internal fun ConversationController.canRemoveProfile(person: String): Boolean {
    val member = members.singleOrNull { it.memberIdHex.equals(person, ignoreCase = true) } ?: return false
    return managementState != null &&
        !isDm &&
        !group.pendingConfirmation &&
        !group.disbanding &&
        !group.disbanded &&
        GroupMemberMenuAction.RemoveMember in
        profileSheetAdminActions(
            viewerIsMember = isSelfMember,
            viewerIsAdmin = isSelfAdmin,
            targetIsMember = true,
            targetIsSelf = person.equals(appState.activeAccount?.accountIdHex, ignoreCase = true),
            targetIsAdmin = isAuthoritativeAdmin(member),
        )
}

/** Transient presentation owners only: authoritative group state and all commits stay in the existing controller. */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun PersonGroupsMembershipContent(
    appState: WhiteNoiseAppState,
    account: String?,
    personHex: String?,
    personName: String,
    groups: List<ChatListItem>,
    ownerIsCurrent: () -> Boolean,
    onFeedback: (ToastMessage?) -> Unit,
    onMembershipChanged: suspend () -> Unit,
    rows: List<PersonSharedGroupRow>,
    unresolved: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    avatar: @Composable (String) -> ImageBitmap?,
    groupAnimation: @Composable (String) -> GroupAvatarAnimation? = { null },
) {
    val currentParentOwner by rememberUpdatedState(ownerIsCurrent)
    // AnimatedContent can retain an outgoing page. Its parent revokes it immediately; disposal
    // permanently retires this visit so returning to the same person cannot revive old callbacks.
    val parentIsCurrent = ownerIsCurrent()
    val visitOwner = remember(account, personHex, parentIsCurrent) { PersonProfileOwner { currentParentOwner() } }
    DisposableEffect(visitOwner) { onDispose { visitOwner.dispose() } }
    var controllers by remember(visitOwner) { mutableStateOf<Map<String, ConversationController>>(emptyMap()) }
    var retry by remember(visitOwner) { mutableIntStateOf(0) }
    var loading by remember(visitOwner) { mutableStateOf(true) }
    var busy by remember(visitOwner) { mutableStateOf(false) }
    var confirmId by remember(visitOwner) { mutableStateOf<String?>(null) }
    LaunchedEffect(visitOwner, groups.map { it.group }, retry) {
        confirmId = null
        controllers = emptyMap()
        loading = true
        val loaded = mutableMapOf<String, ConversationController>()
        try {
            if (account != null && personHex != null && visitOwner.canAct()) {
                groups.forEach { item ->
                    if (visitOwner.canAct()) {
                        appState.profileMembershipController(account, item)?.let { loaded[item.group.groupIdHex] = it }
                    }
                }
                if (visitOwner.canAct()) controllers = loaded
            }
            loading = false
            awaitCancellation()
        } finally {
            loaded.values.forEach { it.onCleared() }
        }
    }

    val labelTemplate = stringResource(R.string.shared_group_remove_person)
    PersonGroupsInCommonContent(
        rows = rows,
        unresolved = unresolved || loading || controllers.size < groups.size,
        onBack = { visitOwner.leave(onBack) },
        onOpen = { id -> if (visitOwner.canAct()) onOpen(id) },
        onAdd = { visitOwner.leave(onAdd) },
        onRetry = {
            if (visitOwner.canAct()) {
                retry++
                onRetry()
            }
        },
        avatar = avatar,
        groupAnimation = groupAnimation,
        removeLabel = { labelTemplate.format(personName, it.title) },
        onRemove = { id -> if (visitOwner.canAct() && !busy) confirmId = id },
        canRemove = { id ->
            visitOwner.canAct() && personHex != null && controllers[id]?.canRemoveProfile(personHex) == true
        },
        busy = busy,
    )
    val id = confirmId
    val row = rows.singleOrNull { it.id == id }
    val controller = controllers[id]
    val target = personHex
    val actingAccountId = appState.accounts.firstOrNull { it.label == account }?.accountIdHex ?: account
    if (id != null &&
        row != null &&
        account != null &&
        target != null &&
        controller?.canRemoveProfile(target) == true &&
        visitOwner.canAct()
    ) {
        ConfirmDialog(
            title = labelTemplate.format(personName, row.title),
            message =
                stringResource(
                    R.string.shared_group_remove_confirmation,
                    personName,
                    appState.npubForDisplay(target),
                    row.title,
                    id,
                    appState.displayName(account),
                    appState.npubForDisplay(checkNotNull(actingAccountId)),
                ),
            confirmLabel = stringResource(R.string.remove_from_group),
            confirmEnabled = !busy,
            destructive = true,
            onDismiss = { if (visitOwner.canAct()) confirmId = null },
            onConfirm = {
                if (visitOwner.canAct() && !busy) {
                    confirmId = null
                    busy = true
                    // The app owns accepted work after navigation. Every attempt creates a fresh authoritative
                    // administration frontier; stale presentation snapshots never authorize a commit.
                    appState.launchMutation {
                        val previousToast = appState.toast
                        var removed = false
                        try {
                            if (visitOwner.canAct() && appState.activeAccountRef == account) {
                                val item = groups.singleOrNull { it.group.groupIdHex == id }
                                val fresh = item?.let { appState.profileMembershipController(account, it) }
                                try {
                                    if (visitOwner.canAct() && fresh?.canRemoveProfile(target) == true) {
                                        val member =
                                            fresh.members.single { it.memberIdHex.equals(target, ignoreCase = true) }
                                        removed = fresh.removeMember(member, refreshAuthorityBeforeCommit = true)
                                    } else if (visitOwner.canAct()) {
                                        appState.present(
                                            R.string.toast_couldnt_remove_member,
                                            R.string.toast_group_roster_changed,
                                        )
                                    }
                                } finally {
                                    fresh?.onCleared()
                                }
                            }
                        } finally {
                            if (visitOwner.canAct()) {
                                if (removed) onMembershipChanged()
                                if (visitOwner.canAct()) {
                                    onFeedback(
                                        if (removed) {
                                            ToastMessage(AppText.Resource(R.string.toast_member_removed))
                                        } else {
                                            personProfileFeedback(previousToast, appState.toast)
                                        },
                                    )
                                    busy = false
                                    retry++
                                    onRetry()
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
