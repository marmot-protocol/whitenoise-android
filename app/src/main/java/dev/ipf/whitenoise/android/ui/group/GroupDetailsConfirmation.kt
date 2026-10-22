@file:Suppress("MatchingDeclarationName") // The confirmation owns its exhaustive presentation models.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags

/** Presentation states only; the screen retains every native permission and mutation callback. */
internal enum class GroupDetailsConfirmationKind {
    RemoveMember,
    TransferAdmin,
    StepDown,
    StepDownSoleAdmin,
    Leave,
    LeaveSoleMember,
    BlockedLeave,
    TransferThenLeave,
}

/** Every state requires exactly the data and action its production copy consumes. */
internal sealed interface GroupDetailsConfirmationState {
    sealed interface Action : GroupDetailsConfirmationState {
        val onConfirm: () -> Unit
    }

    data class RemoveMember(
        val memberName: String,
        override val onConfirm: () -> Unit,
    ) : Action

    data class TransferAdmin(
        val memberName: String,
        override val onConfirm: () -> Unit,
    ) : Action

    data class StepDown(
        override val onConfirm: () -> Unit,
    ) : Action

    data class StepDownSoleAdmin(
        override val onConfirm: () -> Unit,
    ) : Action

    data class Leave(
        val groupName: String,
        override val onConfirm: () -> Unit,
    ) : Action

    data class LeaveSoleMember(
        val groupName: String,
        override val onConfirm: () -> Unit,
    ) : Action

    data class BlockedLeave(
        val groupName: String,
    ) : GroupDetailsConfirmationState

    data class TransferThenLeave(
        val memberName: String,
        override val onConfirm: () -> Unit,
    ) : Action
}

/** Shows the affected identity beside each action while retaining its consequences and destructive role. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
internal fun GroupDetailsConfirmation(
    model: GroupDetailsConfirmationState,
    onDismiss: () -> Unit,
    identity: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    if (model is GroupDetailsConfirmationState.StepDownSoleAdmin) {
        AlertDialog(
            modifier = Modifier.exposePerformanceTestTags(),
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sole_admin_leave_blocked_title)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState()), Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.sole_admin_transfer_hint))
                    identity?.invoke()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = model.onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.testTag("group.details.confirm"),
                ) {
                    Text(stringResource(R.string.transfer_admin))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    if (model is GroupDetailsConfirmationState.BlockedLeave) {
        AlertDialog(
            modifier = Modifier.exposePerformanceTestTags(),
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.confirm_leave_sole_admin_title)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState()), Arrangement.spacedBy(16.dp)) {
                    EmojiLabel(stringResource(R.string.confirm_leave_sole_admin_message, model.groupName))
                    identity?.invoke()
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    val title =
        when (model) {
            is GroupDetailsConfirmationState.RemoveMember -> stringResource(R.string.confirm_remove_member_title)
            is GroupDetailsConfirmationState.TransferAdmin -> stringResource(R.string.confirm_transfer_admin_title)
            is GroupDetailsConfirmationState.StepDown -> stringResource(R.string.step_down_as_admin)
            is GroupDetailsConfirmationState.Leave ->
                stringResource(R.string.confirm_leave_title_named, model.groupName)
            is GroupDetailsConfirmationState.LeaveSoleMember ->
                stringResource(R.string.confirm_leave_sole_member_title, model.groupName)
            is GroupDetailsConfirmationState.TransferThenLeave ->
                stringResource(R.string.confirm_leave_sole_admin_title)
        }
    val subject = if (identity != null) stringResource(R.string.member) else null
    val message =
        when (model) {
            is GroupDetailsConfirmationState.RemoveMember ->
                stringResource(R.string.confirm_remove_member_message, subject ?: model.memberName)
            is GroupDetailsConfirmationState.TransferAdmin ->
                stringResource(R.string.confirm_transfer_admin_message, subject ?: model.memberName)
            is GroupDetailsConfirmationState.StepDown ->
                stringResource(R.string.confirm_step_down_admin_message)
            is GroupDetailsConfirmationState.Leave -> stringResource(R.string.confirm_leave_message_named)
            is GroupDetailsConfirmationState.LeaveSoleMember ->
                stringResource(R.string.confirm_leave_sole_member_message)
            is GroupDetailsConfirmationState.TransferThenLeave ->
                stringResource(R.string.confirm_sole_admin_transfer_then_leave_message, subject ?: model.memberName)
        }
    val action =
        when (model) {
            is GroupDetailsConfirmationState.RemoveMember -> R.string.remove_member
            is GroupDetailsConfirmationState.TransferAdmin -> R.string.transfer_admin
            is GroupDetailsConfirmationState.StepDown -> R.string.step_down_as_admin
            is GroupDetailsConfirmationState.Leave,
            is GroupDetailsConfirmationState.LeaveSoleMember,
            -> R.string.leave
            is GroupDetailsConfirmationState.TransferThenLeave -> R.string.group_action_transfer_and_leave
        }
    ConfirmDialog(
        title = title,
        message = message,
        confirmLabel = stringResource(action),
        onConfirm = (model as GroupDetailsConfirmationState.Action).onConfirm,
        onDismiss = onDismiss,
        destructive = model !is GroupDetailsConfirmationState.TransferAdmin,
        identity = identity,
        confirmEnabled = confirmEnabled,
    )
}

/** Selects who an action affects; an unresolved bound actor never falls back to a different account. */
internal fun groupActionMemberHex(
    kind: GroupDetailsConfirmationKind,
    boundActorHex: String?,
    recipientHex: String?,
): String? =
    when (kind) {
        GroupDetailsConfirmationKind.RemoveMember,
        GroupDetailsConfirmationKind.TransferAdmin,
        GroupDetailsConfirmationKind.TransferThenLeave,
        -> recipientHex
        else -> boundActorHex
    }?.takeIf(String::isNotBlank)
