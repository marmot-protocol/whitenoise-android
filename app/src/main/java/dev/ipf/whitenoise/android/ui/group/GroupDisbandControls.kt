@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.DisbandFailureReasonFfi
import dev.ipf.marmotkit.DisbandRequestFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupScope
import dev.ipf.whitenoise.android.ui.theme.Dimens

/**
 * Admin disband rows for the lifecycle group, driven by the engine's management state: enable (installs the
 * lifecycle component in one commit), then the terminal disband behind [onDisbandRequested]'s confirmation.
 * A pending request renders no rows; [GroupDisbandStatus] shows its progress, failures and blockers.
 */
@Suppress("LongParameterList")
internal fun SettingsGroupScope.groupDisbandRows(
    management: GroupManagementStateFfi?,
    enabled: Boolean,
    enableInProgress: Boolean,
    disbandInProgress: Boolean,
    onEnable: () -> Unit,
    onDisbandRequested: () -> Unit,
) {
    management ?: return
    if (management.disbandRequest is DisbandRequestFfi.Pending) return
    if (management.canEnableDisbanding && !management.disbandingEnabled) {
        row("enable_disbanding") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.group_disband_enable_action),
                onClick = onEnable,
                enabled = enabled,
                leading = { DisbandLeading(inProgress = enableInProgress, destructive = false) },
            )
        }
    }
    if (management.disbandingEnabled && management.canDisband) {
        row("disband") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.group_disband_action),
                onClick = onDisbandRequested,
                enabled = enabled,
                destructive = true,
                leading = { DisbandLeading(inProgress = disbandInProgress, destructive = true) },
            )
        }
    }
}

/** Convergence progress, a failed request with its dismiss, and the engine's blockers under the rows. */
@Composable
internal fun GroupDisbandStatus(
    management: GroupManagementStateFfi?,
    onAcknowledgeFailure: () -> Unit,
) {
    management ?: return
    when (val request = management.disbandRequest) {
        is DisbandRequestFfi.Pending -> {
            DisbandCaptionText(stringResource(R.string.group_disband_pending))
            return
        }
        is DisbandRequestFfi.Failed -> DisbandFailureRows(reason = request.reason, onDismiss = onAcknowledgeFailure)
        else -> Unit
    }
    // Blockers are admin-actionable diagnostics (raw engine identifiers); a non-admin has no disband
    // affordance to explain them against.
    if (management.isSelfAdmin && management.disbandingBlockers.isNotEmpty()) {
        DisbandCaptionText(management.disbandingBlockers.joinToString(separator = "\n"))
    }
}

/** The terminal confirmation before the engine ends the group for everyone. */
@Composable
internal fun GroupDisbandConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.group_disband_dialog_title),
        message = stringResource(R.string.group_disband_dialog_message),
        confirmLabel = stringResource(R.string.group_disband_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

@Composable
private fun DisbandLeading(
    inProgress: Boolean,
    destructive: Boolean,
) {
    if (inProgress) {
        CircularProgressIndicator(modifier = Modifier.size(DisbandProgressSize), strokeWidth = 2.dp)
    } else {
        Icon(
            Icons.Default.DeleteForever,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val DisbandProgressSize = 18.dp

@Composable
private fun DisbandCaptionText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = 8.dp),
    )
}

@Composable
private fun DisbandFailureRows(
    reason: DisbandFailureReasonFfi,
    onDismiss: () -> Unit,
) {
    Text(
        "${stringResource(R.string.group_disband_failed)} · $reason",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = 4.dp),
    )
    TextButton(
        onClick = onDismiss,
        modifier = Modifier.padding(horizontal = Dimens.spaceLg),
    ) {
        Text(stringResource(R.string.dismiss))
    }
}
