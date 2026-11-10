package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.StringRes
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
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
        historyRejoined = controller.groupHistoryNoticeRejoined,
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
        onDismissHistoryNotices = { appState.launchMutation { controller.dismissGroupHistoryNotices() } },
        convergence =
            GroupConvergenceNotice(
                parked = controller.groupConvergenceParked,
                retrying = controller.groupConvergenceRetrying,
                retryFailure = controller.groupConvergenceRetryFailure,
            ),
        onRetryConvergence = { appState.launchMutation { controller.retryGroupConvergence() } },
    )
}

/** What the card shows about a chat whose convergence MDK parked: the parked flag, retry progress and its failure. */
internal data class GroupConvergenceNotice(
    val parked: Boolean = false,
    val retrying: Boolean = false,
    @param:StringRes val retryFailure: Int? = null,
)

/** Whether the conversation shows the recovery card for [status] and the parked flag [convergenceParked]. */
internal fun hasVisibleGroupRecoveryCard(
    status: GroupRecoveryStatusFfi?,
    readFailed: Boolean,
    convergenceParked: Boolean,
): Boolean = readFailed || status.hasVisibleRecoveryState(convergenceParked)

/** Whether the engine status, or a parked convergence, contains information that belongs in the conversation UI. */
internal fun GroupRecoveryStatusFfi?.hasVisibleRecoveryState(convergenceParked: Boolean = false): Boolean =
    convergenceParked ||
        this != null &&
        (
            automaticRecoveryFailed ||
                pendingReinvites > 0u ||
                failedReinvites > 0u ||
                rejoinInvitations.isNotEmpty() ||
                historyMayBeIncomplete
        )

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
    onDismissHistoryNotices: () -> Unit = {},
    historyRejoined: Boolean = false,
    convergence: GroupConvergenceNotice = GroupConvergenceNotice(),
    onRetryConvergence: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!hasVisibleGroupRecoveryCard(status, readFailed, convergence.parked)) return
    var selectedInvitation by remember(status?.groupIdHex) { mutableStateOf<GroupRejoinInvitationFfi?>(null) }
    RecoveryNoticeSurface(modifier) {
        GroupConvergenceParkedNotice(convergence, busy, onRetryConvergence)
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
        GroupHistoryNotice(status, historyRejoined, busy, onDismissHistoryNotices)
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

/**
 * Tells the user that this chat stopped syncing and that their messages wait, with a Retry that shows progress and,
 * when it did not help, a localized reason.
 */
@Composable
@Suppress("FunctionNaming")
private fun GroupConvergenceParkedNotice(
    convergence: GroupConvergenceNotice,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    if (!convergence.parked) return
    Text(
        stringResource(R.string.group_convergence_parked),
        style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
    )
    convergence.retryFailure?.takeUnless { convergence.retrying }?.let { failure ->
        Text(
            stringResource(failure),
            // The retry's outcome appears without focus moving, so TalkBack announces it politely.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            color = MaterialTheme.colorScheme.error,
        )
    }
    val retryingLabel = stringResource(R.string.group_convergence_retrying)
    WhiteNoiseButton(
        onClick = onRetry,
        enabled = !busy,
        loading = convergence.retrying,
        loadingLabel = retryingLabel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.retry))
    }
}

/**
 * Shows the group-scoped warning, worded for a rejoin when [rejoined], and offers dismissal only for active notice ids.
 */
@Composable
@Suppress("FunctionNaming")
private fun GroupHistoryNotice(
    status: GroupRecoveryStatusFfi?,
    rejoined: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    if (status?.historyMayBeIncomplete != true) return
    val wording = if (rejoined) R.string.group_history_before_rejoin else R.string.group_history_may_be_incomplete
    Text(
        stringResource(wording),
        style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
    )
    // Only the user may accept that this history may stay incomplete.
    if (status.historyNoticeIds.isNotEmpty()) {
        TextButton(onClick = onDismiss, enabled = !busy) {
            Text(stringResource(R.string.dismiss))
        }
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
            // The prototype's notice hugs its text up to 440 dp rather than filling the row.
            modifier = Modifier.widthIn(max = 440.dp),
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
                EmojiLabel(stringResource(R.string.group_rejoin_invited_by, inviterName, inviterIdentity))
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
