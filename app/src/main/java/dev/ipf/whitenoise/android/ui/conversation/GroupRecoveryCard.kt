package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.R

/** Whether the engine status contains information that belongs in the conversation UI. */
internal fun GroupRecoveryStatusFfi.hasVisibleRecoveryState(): Boolean =
    automaticRecoveryFailed || pendingReinvites > 0u || failedReinvites > 0u || rejoinInvitations.isNotEmpty()

/** Presents engine-owned recovery state without interpreting it as membership evidence. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupRecoveryCard(
    status: GroupRecoveryStatusFfi?,
    busy: Boolean,
    inviterName: (String) -> String,
    inviterIdentity: (String) -> String,
    onConfirm: (GroupRejoinInvitationFfi) -> Unit,
    onDecline: (GroupRejoinInvitationFfi) -> Unit,
    readFailed: Boolean = false,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!readFailed && status?.hasVisibleRecoveryState() != true) return
    var selectedInvitation by remember(status?.groupIdHex) { mutableStateOf<GroupRejoinInvitationFfi?>(null) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status?.automaticRecoveryFailed == true) {
                Text(stringResource(R.string.group_recovery_automatic_failed))
            }
            if ((status?.pendingReinvites ?: 0u) > 0u) {
                Text(stringResource(R.string.group_recovery_pending_reinvites))
            }
            if ((status?.failedReinvites ?: 0u) > 0u) {
                Text(stringResource(R.string.group_recovery_failed_reinvites))
            }
            status?.rejoinInvitations.orEmpty().forEach { invitation ->
                OutlinedButton(
                    onClick = { selectedInvitation = invitation },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.group_recovery_review_invitation))
                }
            }
            if (readFailed) {
                Text(stringResource(R.string.group_recovery_check_failed))
                TextButton(onClick = onRetry, enabled = !busy) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }

    selectedInvitation?.let { invitation ->
        GroupRejoinInvitationDialog(
            invitation = invitation,
            busy = busy,
            inviterName = inviterName(invitation.welcomerAccountIdHex),
            inviterIdentity = inviterIdentity(invitation.welcomerAccountIdHex),
            onDismiss = { selectedInvitation = null },
            onConfirm = onConfirm,
            onDecline = onDecline,
        )
    }
}

/** Confirms or declines one exact, already-reviewed rejoin offer. */
@Composable
@Suppress("FunctionNaming")
private fun GroupRejoinInvitationDialog(
    invitation: GroupRejoinInvitationFfi,
    busy: Boolean,
    inviterName: String,
    inviterIdentity: String,
    onDismiss: () -> Unit,
    onConfirm: (GroupRejoinInvitationFfi) -> Unit,
    onDecline: (GroupRejoinInvitationFfi) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.group_rejoin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.group_rejoin_invited_by, inviterName, inviterIdentity))
                Text(stringResource(R.string.group_rejoin_replaces_state))
                Text(stringResource(R.string.group_rejoin_trust_warning))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(invitation)
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.group_rejoin_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDecline(invitation)
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.group_rejoin_decline))
            }
        },
    )
}
