@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.profile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.removeMember
import dev.ipf.whitenoise.android.state.setMemberAdmin
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMembersReviewDialog
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.common.rememberConversationGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.group.groupMemberMenuActions
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.SettingsSection

/** Group admin actions the viewer may take on this profile's member; empty when the target is not a member. */
internal fun profileSheetAdminActions(
    viewerIsMember: Boolean,
    viewerIsAdmin: Boolean,
    targetIsMember: Boolean,
    targetIsSelf: Boolean,
    targetIsAdmin: Boolean,
): List<GroupMemberMenuAction> {
    if (!targetIsMember) return emptyList()
    return groupMemberMenuActions(
        viewerIsMember = viewerIsMember,
        viewerIsAdmin = viewerIsAdmin,
        targetIsSelf = targetIsSelf,
        targetIsAdmin = targetIsAdmin,
    ).filter { it != GroupMemberMenuAction.StepDownAsAdmin }
}

/** Test tag for the profile sheet row that runs [action]. */
internal fun adminActionRowTag(action: GroupMemberMenuAction): String = "profile-admin-action-${action.name}"

internal const val PROFILE_SHEET_ADMIN_ACTIONS_TAG = "profile-sheet-admin-actions"

/**
 * Starts [mutation] for [action] unless another one is running, and clears the pending action when it ends.
 * Returns false when a mutation was already in flight.
 */
internal fun runProfileSheetAdminMutation(
    action: GroupMemberMenuAction,
    isBusy: () -> Boolean,
    onPendingActionChange: (GroupMemberMenuAction?) -> Unit,
    clearLastMutationError: () -> Unit,
    launchMutation: (suspend () -> Unit) -> Unit,
    mutation: suspend () -> Unit,
): Boolean {
    if (isBusy()) return false
    onPendingActionChange(action)
    clearLastMutationError()
    launchMutation {
        try {
            mutation()
        } finally {
            onPendingActionChange(null)
        }
    }
    return true
}

/**
 * The group-scoped half of a member profile: a labelled Group Actions section whose rows promote,
 * demote, or remove the member. Only an admin viewing someone else receives any [actions] at all.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ProfileSheetAdminActionRows(
    actions: List<GroupMemberMenuAction>,
    pendingAction: GroupMemberMenuAction?,
    busy: Boolean,
    onGrantAdmin: () -> Unit,
    onRevokeAdmin: () -> Unit,
    onRemoveMember: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(PROFILE_SHEET_ADMIN_ACTIONS_TAG)) {
        SettingsSection(stringResource(R.string.group_actions))
        SettingsGroup {
            actions.forEach { action ->
                when (action) {
                    GroupMemberMenuAction.GrantAdmin ->
                        row(action.name) { rowContext ->
                            ProfileSheetAdminActionRow(
                                context = rowContext,
                                action = action,
                                title = stringResource(R.string.make_admin),
                                icon = R.drawable.ic_admin_panel_settings,
                                inProgress = pendingAction == action,
                                enabled = !busy,
                                onClick = onGrantAdmin,
                            )
                        }
                    GroupMemberMenuAction.RevokeAdmin ->
                        row(action.name) { rowContext ->
                            ProfileSheetAdminActionRow(
                                context = rowContext,
                                action = action,
                                title = stringResource(R.string.remove_admin),
                                icon = R.drawable.ic_person,
                                inProgress = pendingAction == action,
                                enabled = !busy,
                                onClick = onRevokeAdmin,
                            )
                        }
                    GroupMemberMenuAction.RemoveMember ->
                        row(action.name) { rowContext ->
                            ProfileSheetAdminActionRow(
                                context = rowContext,
                                action = action,
                                title = stringResource(R.string.remove_from_group),
                                icon = R.drawable.ic_person_remove,
                                inProgress = pendingAction == action,
                                enabled = !busy,
                                destructive = true,
                                onClick = onRemoveMember,
                            )
                        }
                    // Self is excluded on this surface, so StepDownAsAdmin never
                    // appears (it is filtered out by profileSheetAdminActions).
                    GroupMemberMenuAction.StepDownAsAdmin -> Unit
                }
            }
        }
    }
}

/**
 * One moderation row. A running mutation swaps the leading glyph for a same-sized indicator, so the
 * row that owns the work says so without the block changing height or the label moving.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun ProfileSheetAdminActionRow(
    context: SettingsRowContext,
    action: GroupMemberMenuAction,
    title: String,
    @DrawableRes icon: Int,
    inProgress: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    SettingsAction(
        context = context,
        title = title,
        onClick = onClick,
        modifier = Modifier.testTag(adminActionRowTag(action)),
        enabled = enabled,
        destructive = destructive,
        leading = {
            if (inProgress) {
                CircularProgressIndicator(Modifier.size(ProfileAdminGlyphSize))
            } else {
                SettingsLeadingIcon(icon)
            }
        },
    )
}

/** Moderation glyph box; the busy indicator matches the icon so the row never reflows. */
private val ProfileAdminGlyphSize = 24.dp

/** Keeps pre-mutation admin status stable while a grant or revoke action is pending. */
internal fun stableAdminActionTargetIsAdmin(
    authoritativeAdmin: Boolean,
    pendingAction: GroupMemberMenuAction?,
): Boolean =
    when (pendingAction) {
        GroupMemberMenuAction.GrantAdmin -> false
        GroupMemberMenuAction.RevokeAdmin -> true
        else -> authoritativeAdmin
    }

/**
 * Group-admin moderation block shown inside the in-conversation profile sheet
 * (issue #635). Resolves the viewed user's member record in [controller] by a
 * case-insensitive hex match (consistent with the rest of the file), then asks
 * [profileSheetAdminActions] which actions are in scope — the same rules and
 * engine calls the Group Info members list uses (#444). Renders nothing when
 * scope fails (viewer not an admin member, viewed user is self or not a member
 * of this group), so the sheet is unchanged for those cases.
 *
 * Mutations run through [WhiteNoiseAppState.launchMutation] (process-lifetime
 * scope) — the same approach as GroupDetailsScreen's local runGroupMutation — so
 * the MLS commit + Nostr publish and the controller's own refreshMembers/toast
 * finish even if the sheet dismisses mid-flight. The locally pending action plus
 * the controller's [ConversationController.mutationInFlight] disable the buttons;
 * only the locally started action shows an in-row spinner.
 */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ProfileSheetAdminActions(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    targetHex: String,
    isCurrent: () -> Boolean = { true },
    onFeedback: (ToastMessage?) -> Unit = {},
) {
    // Keep the initiated action stable until its coroutine completes. Detailed
    // MDK state can land one frame before the local pending flag clears.
    var pendingAction by remember(controller, targetHex) { mutableStateOf<GroupMemberMenuAction?>(null) }
    var confirmRemove by remember(controller, targetHex) { mutableStateOf(false) }
    var confirmRoleChange by remember(controller, targetHex) { mutableStateOf<GroupMemberMenuAction?>(null) }
    val targetMember =
        remember(controller.presentedMembers, targetHex) {
            controller.presentedMembers.firstOrNull { it.memberIdHex.equals(targetHex, ignoreCase = true) }
        }
    // Keep the action label stable while the optimistic badge changes. The row
    // remains disabled until MDK reconciles, then switches to the inverse action.
    val targetWasAuthoritativelyAdmin = targetMember?.let { controller.isAuthoritativeAdmin(it) } == true
    val actionTargetIsAdmin = stableAdminActionTargetIsAdmin(targetWasAuthoritativelyAdmin, pendingAction)
    val targetIsSelf =
        targetMember?.let {
            GroupProjector.isActiveAccountMember(it, appState.activeAccount?.accountIdHex)
        } == true
    val actions =
        profileSheetAdminActions(
            viewerIsMember = controller.isSelfMember,
            viewerIsAdmin = controller.isSelfAdmin,
            targetIsMember = targetMember != null,
            targetIsSelf = targetIsSelf,
            targetIsAdmin = actionTargetIsAdmin,
        )
    if (targetMember == null || actions.isEmpty()) return

    // The action-scoped local state both disables immediately and identifies the
    // row that owns progress. mutationInFlight only disables for work started
    // elsewhere; it must not assign that work to a row in this sheet.
    val busy = !isCurrent() || pendingAction != null || controller.mutationInFlight

    /** Runs a member menu mutation under the shared lock. */
    fun runMutation(
        action: GroupMemberMenuAction,
        mutation: suspend () -> Unit,
    ) {
        if (!isCurrent()) return
        runProfileSheetAdminMutation(
            action = action,
            isBusy = { pendingAction != null || controller.mutationInFlight },
            onPendingActionChange = { pendingAction = it },
            clearLastMutationError = controller::clearLastMutationError,
            launchMutation = appState::launchMutation,
            mutation = {
                if (isCurrent()) {
                    val previousToast = appState.toast
                    mutation()
                    if (isCurrent()) onFeedback(personProfileFeedback(previousToast, appState.toast))
                }
            },
        )
    }

    ProfileSheetAdminActionRows(
        actions = actions,
        pendingAction = pendingAction,
        busy = busy,
        onGrantAdmin = { if (isCurrent()) confirmRoleChange = GroupMemberMenuAction.GrantAdmin },
        onRevokeAdmin = { if (isCurrent()) confirmRoleChange = GroupMemberMenuAction.RevokeAdmin },
        onRemoveMember = { if (isCurrent()) confirmRemove = true },
    )

    val groupAvatar = rememberConversationGroupAvatar(appState, controller)
    val reviewGroup =
        MemberReviewGroup(
            controller.group.groupIdHex,
            controller.title(rememberGroupTitleCopy()),
            groupAvatar.pictureUrl,
            groupAvatar.image,
            groupAnimation = groupAvatar.groupAnimation,
        )
    val reviewMember =
        RecipientSearch.Candidate(
            targetHex,
            controller.memberDisplayName(targetMember),
            appState.npubForDisplay(targetHex),
        )
    confirmRoleChange?.let { roleChange ->
        val promoting = roleChange == GroupMemberMenuAction.GrantAdmin
        ProfileSheetModerationConfirmDialog(
            appState = appState,
            group = reviewGroup,
            member = reviewMember,
            busy = busy,
            title = stringResource(if (promoting) R.string.make_admin else R.string.remove_admin),
            confirmLabel = stringResource(if (promoting) R.string.make_admin else R.string.remove_admin),
            description = if (promoting) null else stringResource(R.string.group_action_revoke_explanation),
            onConfirm = {
                confirmRoleChange = null
                runMutation(roleChange) {
                    controller.setMemberAdmin(targetMember, admin = promoting)
                }
            },
            onDismiss = { confirmRoleChange = null },
        )
    }

    if (confirmRemove) {
        ProfileSheetModerationConfirmDialog(
            appState = appState,
            group = reviewGroup,
            member = reviewMember,
            busy = busy,
            title = stringResource(R.string.remove_from_group),
            confirmLabel = stringResource(R.string.remove_from_group),
            onConfirm = {
                confirmRemove = false
                runMutation(GroupMemberMenuAction.RemoveMember) {
                    controller.removeMember(targetMember)
                }
            },
            onDismiss = { confirmRemove = false },
            destructive = true,
        )
    }
}

/** Reviews the public member and destination before the existing scoped mutation runs. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun ProfileSheetModerationConfirmDialog(
    appState: WhiteNoiseAppState,
    group: MemberReviewGroup,
    member: RecipientSearch.Candidate,
    busy: Boolean,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    description: String? = null,
) {
    GroupMembersReviewDialog(
        appState = appState,
        groups = listOf(group),
        members = listOf(member),
        busy = busy,
        title = title,
        description = description,
        invitation = false,
        dismissEnabled = true,
        showProgress = false,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = destructive,
    )
}
