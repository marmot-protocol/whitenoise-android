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

/** Transient presentation only; every mounted visit permanently owns its callbacks and controllers. */
internal class MembershipVisitState(
    val owner: PersonProfileOwner,
) {
    var controllers by mutableStateOf<Map<String, ConversationController>>(emptyMap())
    var retry by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var confirmId by mutableStateOf<String?>(null)

    fun canRemove(
        id: String,
        person: String?,
    ): Boolean = owner.canAct() && person != null && controllers[id]?.canRemoveProfile(person) == true
}

private data class MembershipRemovalContext(
    val row: PersonSharedGroupRow,
    val account: String,
    val person: String,
)

/** Transient presentation owners only: authoritative group state and all commits stay in the existing controller. */
@Suppress("FunctionNaming", "LongParameterList")
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
    val state = rememberMembershipVisit(account, personHex, ownerIsCurrent)
    MembershipControllersEffect(state, appState, account, personHex, groups)
    val labelTemplate = stringResource(R.string.shared_group_remove_person)
    PersonGroupsInCommonContent(
        rows = rows,
        unresolved = unresolved,
        onBack = { state.owner.leave(onBack) },
        onOpen = { id -> if (state.owner.canAct()) onOpen(id) },
        onAdd = { state.owner.leave(onAdd) },
        onRetry = {
            if (state.owner.canAct()) {
                state.retry++
                onRetry()
            }
        },
        avatar = avatar,
        groupAnimation = groupAnimation,
        removeLabel = { labelTemplate.format(personName, it.title) },
        onRemove = { id -> if (state.owner.canAct() && !state.busy) state.confirmId = id },
        canRemove = { state.canRemove(it, personHex) },
        busy = state.busy,
    )
    membershipRemovalContext(state, rows, account, personHex)?.let { context ->
        MembershipRemovalConfirmation(appState, state, context, personName, labelTemplate) {
            launchMembershipRemoval(appState, state, context, groups, onMembershipChanged, onFeedback, onRetry)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun rememberMembershipVisit(
    account: String?,
    person: String?,
    parentIsCurrent: () -> Boolean,
): MembershipVisitState {
    val currentParentOwner by rememberUpdatedState(parentIsCurrent)
    // Outgoing AnimatedContent is invalidated immediately, and disposal permanently retires old callbacks.
    val current = parentIsCurrent()
    val owner = remember(account, person, current) { PersonProfileOwner { currentParentOwner() } }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    return remember(owner) { MembershipVisitState(owner) }
}

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun MembershipControllersEffect(
    state: MembershipVisitState,
    appState: WhiteNoiseAppState,
    account: String?,
    person: String?,
    groups: List<ChatListItem>,
) {
    LaunchedEffect(state, groups.map { it.group }, state.retry) {
        state.confirmId = null
        state.controllers = emptyMap()
        val loaded = mutableMapOf<String, ConversationController>()
        try {
            if (account != null && person != null && state.owner.canAct()) {
                groups.forEach { item ->
                    if (state.owner.canAct()) {
                        appState.profileMembershipController(account, item)?.let { loaded[item.group.groupIdHex] = it }
                    }
                }
                if (state.owner.canAct()) state.controllers = loaded
            }
            awaitCancellation()
        } finally {
            loaded.values.forEach { it.onCleared() }
        }
    }
}

private fun membershipRemovalContext(
    state: MembershipVisitState,
    rows: List<PersonSharedGroupRow>,
    account: String?,
    person: String?,
): MembershipRemovalContext? =
    if (account == null || person == null) {
        null
    } else {
        state.confirmId?.let { id ->
            rows
                .singleOrNull { it.id == id }
                ?.takeIf { state.canRemove(id, person) }
                ?.let { MembershipRemovalContext(it, account, person) }
        }
    }

@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun MembershipRemovalConfirmation(
    appState: WhiteNoiseAppState,
    state: MembershipVisitState,
    context: MembershipRemovalContext,
    personName: String,
    labelTemplate: String,
    onConfirm: () -> Unit,
) {
    val actingAccountId = appState.accounts.firstOrNull { it.label == context.account }?.accountIdHex ?: context.account
    ConfirmDialog(
        title = labelTemplate.format(personName, context.row.title),
        message =
            stringResource(
                R.string.shared_group_remove_confirmation,
                personName,
                appState.npubForDisplay(context.person),
                context.row.title,
                context.row.id,
                appState.displayName(context.account),
                appState.npubForDisplay(actingAccountId),
            ),
        confirmLabel = stringResource(R.string.remove_from_group),
        confirmEnabled = !state.busy,
        destructive = true,
        onDismiss = { if (state.owner.canAct()) state.confirmId = null },
        onConfirm = {
            if (state.owner.canAct() && !state.busy) {
                state.confirmId = null
                state.busy = true
                onConfirm()
            }
        },
    )
}

/** Accepted native work keeps the app owner while presentation callbacks remain scoped to this visit. */
@Suppress("LongParameterList")
private fun launchMembershipRemoval(
    appState: WhiteNoiseAppState,
    state: MembershipVisitState,
    context: MembershipRemovalContext,
    groups: List<ChatListItem>,
    onMembershipChanged: suspend () -> Unit,
    onFeedback: (ToastMessage?) -> Unit,
    onRetry: () -> Unit,
) {
    appState.launchMutation {
        val previousToast = appState.toast
        var removed = false
        try {
            removed = performMembershipRemoval(appState, state.owner, context, groups)
        } finally {
            completeMembershipRemoval(appState, state, removed, previousToast, onMembershipChanged, onFeedback, onRetry)
        }
    }
}

private suspend fun performMembershipRemoval(
    appState: WhiteNoiseAppState,
    owner: PersonProfileOwner,
    context: MembershipRemovalContext,
    groups: List<ChatListItem>,
): Boolean {
    if (!owner.canAct() || appState.activeAccountRef != context.account) return false
    val item = groups.singleOrNull { it.group.groupIdHex == context.row.id }
    val fresh = item?.let { appState.profileMembershipController(context.account, it) }
    return try {
        if (owner.canAct() && fresh?.canRemoveProfile(context.person) == true) {
            val member = fresh.members.single { it.memberIdHex.equals(context.person, ignoreCase = true) }
            fresh.removeMember(member, refreshAuthorityBeforeCommit = true)
        } else {
            if (owner.canAct()) {
                appState.present(R.string.toast_couldnt_remove_member, R.string.toast_group_roster_changed)
            }
            false
        }
    } finally {
        fresh?.onCleared()
    }
}

@Suppress("LongParameterList")
internal suspend fun completeMembershipRemoval(
    appState: WhiteNoiseAppState,
    state: MembershipVisitState,
    removed: Boolean,
    previousToast: ToastMessage?,
    onMembershipChanged: suspend () -> Unit,
    onFeedback: (ToastMessage?) -> Unit,
    onRetry: () -> Unit,
) {
    // The parent profile owns its account-wide refresh even after this child visit has retired.
    if (removed) onMembershipChanged()
    if (state.owner.canAct()) {
        val feedback =
            if (removed) {
                ToastMessage(AppText.Resource(R.string.toast_member_removed))
            } else {
                personProfileFeedback(previousToast, appState.toast)
            }
        onFeedback(feedback)
        state.busy = false
        state.retry++
        onRetry()
    }
}
