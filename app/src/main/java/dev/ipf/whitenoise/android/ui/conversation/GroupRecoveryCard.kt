package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.dismissGroupCommitRefusal
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Lets a tall recovery card scroll inside the space left by the composer. */
@Composable
@Suppress("FunctionNaming")
internal fun EmptyConversationRecoveryContent(
    state: ConversationEmptyState,
    modifier: Modifier = Modifier,
    recovery: @Composable () -> Unit,
) {
    Column(modifier) {
        Column(Modifier.fadingVerticalScroll()) { recovery() }
        ConversationEmptyMessage(state, verticalPadding = 0.dp)
    }
}

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
        refusal =
            GroupCommitRefusalNoticeModel(
                refusedRelays = controller.groupCommitRefusal.refusedRelays,
                dismissing = controller.groupCommitRefusal.dismissing,
                dismissFailure = controller.groupCommitRefusal.dismissFailure,
            ),
        onDismissCommitRefusal = { appState.launchMutation { controller.dismissGroupCommitRefusal() } },
    )
}

/** What the card shows about a chat whose convergence MDK parked: the parked flag, retry progress and its failure. */
internal data class GroupConvergenceNotice(
    val parked: Boolean = false,
    val retrying: Boolean = false,
    @param:StringRes val retryFailure: Int? = null,
)

/**
 * Whether the conversation shows the recovery card for [status], the parked flag [convergenceParked] and a relay's
 * lasting refusal of one of the user's changes, [commitRefused].
 */
internal fun hasVisibleGroupRecoveryCard(
    status: GroupRecoveryStatusFfi?,
    readFailed: Boolean,
    convergenceParked: Boolean,
    commitRefused: Boolean = false,
): Boolean = readFailed || commitRefused || status.hasVisibleRecoveryState(convergenceParked)

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
    refusal: GroupCommitRefusalNoticeModel = GroupCommitRefusalNoticeModel(),
    onDismissCommitRefusal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!hasVisibleGroupRecoveryCard(status, readFailed, convergence.parked, refusal.refusedRelays.isNotEmpty())) return
    var selectedInvitation by remember(status?.groupIdHex) { mutableStateOf<GroupRejoinInvitationFfi?>(null) }
    RecoveryNoticeSurface(modifier) {
        GroupConvergenceParkedNotice(convergence, busy, onRetryConvergence)
        GroupCommitRefusalNotice(refusal, busy, onDismissCommitRefusal)
        RecoveryStatusLine(R.string.group_recovery_automatic_failed, status?.automaticRecoveryFailed == true)
        RecoveryStatusLine(R.string.group_recovery_pending_reinvites, (status?.pendingReinvites ?: 0u) > 0u)
        RecoveryStatusLine(R.string.group_recovery_failed_reinvites, (status?.failedReinvites ?: 0u) > 0u)
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
                style = MaterialTheme.typography.bodyMedium,
            )
            WhiteNoiseButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.retry))
            }
        }
    }

    selectedInvitation?.takeIf { it in status?.rejoinInvitations.orEmpty() }?.let { invitation ->
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

/** One line of the engine's recovery status, shown only while [visible]. */
@Composable
@Suppress("FunctionNaming")
private fun RecoveryStatusLine(
    @StringRes text: Int,
    visible: Boolean,
) {
    if (!visible) return
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
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
        style = MaterialTheme.typography.bodyMedium,
    )
    convergence.retryFailure?.takeUnless { convergence.retrying }?.let { failure ->
        Text(
            stringResource(failure),
            // The retry's outcome appears without focus moving, so TalkBack announces it politely.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
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
        style = MaterialTheme.typography.bodyMedium,
    )
    // Only the user may accept that this history may stay incomplete.
    if (status.historyNoticeIds.isNotEmpty()) {
        TextButton(onClick = onDismiss, enabled = !busy) {
            Text(stringResource(R.string.dismiss))
        }
    }
}

/** Gives the callout the timeline's section spacing above and below adjacent rows. */
@Composable
@Suppress("FunctionNaming")
private fun RecoveryNoticeSurface(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.NoticeHorizontalInset, vertical = WhiteNoiseSpacing.Section),
        contentAlignment = Alignment.Center,
    ) {
        WhiteNoiseCallout(modifier = Modifier.widthIn(max = 440.dp), content = content)
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
    val currentBusy = rememberUpdatedState(busy)
    val currentInvitation = rememberUpdatedState(invitation)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentOnConfirm = rememberUpdatedState(onConfirm)
    val currentOnDecline = rememberUpdatedState(onDecline)
    key(invitation, busy) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            dismissEnabled = { !busy },
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
                        close {
                            if (!currentBusy.value && currentInvitation.value == invitation) {
                                currentOnDismiss.value()
                                currentOnConfirm.value(invitation)
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.group_rejoin_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        close {
                            if (!currentBusy.value && currentInvitation.value == invitation) {
                                currentOnDismiss.value()
                                currentOnDecline.value(invitation)
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.group_rejoin_decline))
                }
            },
        )
    }
}
