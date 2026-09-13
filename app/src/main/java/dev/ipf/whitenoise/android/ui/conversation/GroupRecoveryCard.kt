package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Wires the conversation-owned recovery actions consistently in empty and populated timelines. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationGroupRecoveryCard(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
) {
    GroupRecoveryCard(
        status = controller.groupRecoveryStatus,
        busy = controller.groupRecoveryMutationInFlight,
        inviterName = appState::displayName,
        inviterIdentity = appState::npubForDisplay,
        onConfirm = { invitation ->
            appState.launchMutation { controller.confirmGroupRejoin(invitation) }
        },
        onDecline = { invitation ->
            appState.launchMutation { controller.declineGroupRejoin(invitation) }
        },
        readFailed = controller.groupRecoveryReadFailed,
        onRetry = { appState.launchMutation { controller.retryGroupRecoveryStatus() } },
    )
}

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
    RecoveryNoticeSurface(modifier) {
        if (status?.automaticRecoveryFailed == true) {
            Text(
                stringResource(R.string.group_recovery_automatic_failed),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            )
        }
        if ((status?.pendingReinvites ?: 0u) > 0u) {
            Text(
                stringResource(R.string.group_recovery_pending_reinvites),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            )
        }
        if ((status?.failedReinvites ?: 0u) > 0u) {
            Text(
                stringResource(R.string.group_recovery_failed_reinvites),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            )
        }
        status?.rejoinInvitations.orEmpty().forEach { invitation ->
            WhiteNoiseOutlinedButton(
                onClick = { selectedInvitation = invitation },
                enabled = !busy,
            ) {
                Text(
                    stringResource(
                        R.string.group_recovery_review_invitation_from,
                        inviterName(invitation.welcomerAccountIdHex),
                        invitation.epoch.toString(),
                    ),
                )
            }
        }
        if (readFailed) {
            Text(
                stringResource(R.string.group_recovery_check_failed),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            )
            WhiteNoiseButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.retry))
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

/** Applies prototype timeline-notice chrome to the retained native recovery controls. */
@Composable
@Suppress("FunctionNaming")
private fun RecoveryNoticeSurface(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = amoledOutlineBorder(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
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
