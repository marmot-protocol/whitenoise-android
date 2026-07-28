@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.MaterialTheme
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
import dev.ipf.marmotkit.DisbandFailureReasonFfi
import dev.ipf.marmotkit.DisbandRequestFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.newchat.DangerActionRow
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.theme.Dimens

/**
 * Admin disband controls, driven entirely by the engine's management state:
 * enable (installs the lifecycle component in one commit), then the terminal
 * disband behind an explicit confirmation. An in-flight request renders as
 * progress instead of actions; a failed one surfaces its reason with a
 * dismiss that re-arms the action. Blockers the engine reports are shown
 * verbatim under the disabled action.
 */
@Composable
internal fun GroupDetailsDisbandControls(
    management: GroupManagementStateFfi?,
    enabled: Boolean,
    enableInProgress: Boolean,
    disbandInProgress: Boolean,
    onEnable: () -> Unit,
    onDisbandConfirmed: () -> Unit,
    onAcknowledgeFailure: () -> Unit,
) {
    management ?: return
    var confirmOpen by remember { mutableStateOf(false) }
    val request = management.disbandRequest
    if (request is DisbandRequestFfi.Pending) {
        // The engine owns convergence from here; render progress only.
        DisbandCaptionText(stringResource(R.string.group_disband_pending))
        return
    }
    if (request is DisbandRequestFfi.Failed) {
        DisbandFailureRows(reason = request.reason, onDismiss = onAcknowledgeFailure)
    }
    if (management.canEnableDisbanding && !management.disbandingEnabled) {
        DangerActionRow(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.group_disband_enable_action),
            enabled = enabled,
            inProgress = enableInProgress,
            onClick = onEnable,
        )
    }
    if (management.disbandingEnabled && management.canDisband) {
        DangerActionRow(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.group_disband_action),
            enabled = enabled,
            inProgress = disbandInProgress,
            onClick = { confirmOpen = true },
        )
    }
    // Blockers are admin-actionable diagnostics (raw engine identifiers); a
    // non-admin has no disband affordance to explain them against.
    if (management.isSelfAdmin && management.disbandingBlockers.isNotEmpty()) {
        DisbandCaptionText(management.disbandingBlockers.joinToString(separator = "\n"))
    }
    if (confirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.group_disband_dialog_title),
            message = stringResource(R.string.group_disband_dialog_message),
            confirmLabel = stringResource(R.string.group_disband_confirm),
            onConfirm = {
                confirmOpen = false
                onDisbandConfirmed()
            },
            onDismiss = { confirmOpen = false },
            destructive = true,
        )
    }
}

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
