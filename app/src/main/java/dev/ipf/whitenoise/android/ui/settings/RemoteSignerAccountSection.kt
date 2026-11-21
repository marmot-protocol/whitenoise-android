package dev.ipf.whitenoise.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.marmotkit.Nip46PermissionsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RemoteSignerAccountStatus
import dev.ipf.whitenoise.android.state.RemoteSignerHealth
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.approveRemoteSignerPermissions
import dev.ipf.whitenoise.android.state.completeRemoteSignerApproval
import dev.ipf.whitenoise.android.state.disconnectRemoteSigner
import dev.ipf.whitenoise.android.state.reconnectRemoteSigner
import dev.ipf.whitenoise.android.state.reviewRemoteSignerPermissions
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerFlow
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.RemoteSignerPermissionReview
import dev.ipf.whitenoise.android.ui.onboarding.remotesigner.remoteSignerApprovalHost

/** What the user can do for a remote-signer account from Account keys. */
internal enum class RemoteSignerAccountAction(
    val tag: String,
    val titleRes: Int,
) {
    Retry("remote_signer.account.retry", R.string.remote_signer_action_retry),
    OpenApproval("remote_signer.account.open_approval", R.string.remote_signer_approval_open),
    FinishApproval("remote_signer.account.approval_done", R.string.remote_signer_approval_done),
    ReviewPermissions("remote_signer.account.review", R.string.remote_signer_action_review),
    PairAgain("remote_signer.account.pair_again", R.string.remote_signer_action_pair_again),
    Disconnect("remote_signer.account.disconnect", R.string.remote_signer_action_disconnect),
}

/** One remote-signer account's status and the recovery Account keys offers for it. */
internal class RemoteSignerAccountActions(
    val status: RemoteSignerAccountStatus,
    val approvalOpened: Boolean,
    val hasSession: Boolean,
    val onAction: (RemoteSignerAccountAction) -> Unit,
) {
    /** The approval page's host, shown before it can be opened; null for a page that is never opened. */
    val approvalHost: String? = status.authUrl?.let(::remoteSignerApprovalHost)

    /** Only the recovery that fits the current state; a healthy signer offers just Disconnect. */
    val available: List<RemoteSignerAccountAction>
        get() =
            when (status.health) {
                RemoteSignerHealth.ApprovalNeeded ->
                    if (approvalHost == null) {
                        // A page White Noise will not open: retrying asks the signer again instead.
                        listOf(RemoteSignerAccountAction.Retry)
                    } else {
                        listOfNotNull(
                            RemoteSignerAccountAction.OpenApproval,
                            RemoteSignerAccountAction.FinishApproval.takeIf { approvalOpened },
                        )
                    }
                RemoteSignerHealth.PermissionReview -> listOf(RemoteSignerAccountAction.ReviewPermissions)
                RemoteSignerHealth.Unreachable, RemoteSignerHealth.Declined, RemoteSignerHealth.Disconnected ->
                    listOf(RemoteSignerAccountAction.Retry, RemoteSignerAccountAction.PairAgain)
                RemoteSignerHealth.PairingEnded -> listOf(RemoteSignerAccountAction.PairAgain)
                RemoteSignerHealth.Paired, RemoteSignerHealth.Connecting, RemoteSignerHealth.Reconnecting -> emptyList()
            } + listOfNotNull(RemoteSignerAccountAction.Disconnect.takeIf { hasSession && status.health.canDisconnect })
}

/** Status line for [health]; healthy states read as plain status, others say what still works. */
internal fun remoteSignerStatusRes(health: RemoteSignerHealth): Int =
    when (health) {
        RemoteSignerHealth.Paired -> R.string.remote_signer_status_paired
        RemoteSignerHealth.Connecting -> R.string.remote_signer_status_connecting
        RemoteSignerHealth.Reconnecting -> R.string.remote_signer_status_reconnecting
        RemoteSignerHealth.ApprovalNeeded -> R.string.remote_signer_status_approval
        RemoteSignerHealth.PermissionReview -> R.string.remote_signer_status_permissions
        RemoteSignerHealth.Unreachable -> R.string.remote_signer_status_unreachable
        RemoteSignerHealth.Declined -> R.string.remote_signer_status_declined
        RemoteSignerHealth.Disconnected -> R.string.remote_signer_status_disconnected
        RemoteSignerHealth.PairingEnded -> R.string.remote_signer_status_ended
    }

/**
 * Remembers the remote-signer actions for [accountIdHex], or null for any other account, and hosts their dialogs
 * and the full-screen re-pair flow.
 */
@Suppress("FunctionNaming")
@Composable
internal fun rememberRemoteSignerAccountActions(
    appState: WhiteNoiseAppState,
    accountIdHex: String?,
): RemoteSignerAccountActions? {
    val statuses by appState.remoteSigners.statuses.collectAsState()
    val status = accountIdHex?.let { statuses[it] }
    val account = accountIdHex.orEmpty()
    val context = LocalContext.current
    var dialog by remember(account) { mutableStateOf<RemoteSignerAccountAction?>(null) }
    var review by remember(account) { mutableStateOf<Nip46PermissionsFfi?>(null) }
    var approvalOpened by remember(account, status?.requestId) { mutableStateOf(false) }
    RemoteSignerAccountDialogs(appState, account, dialog, review) {
        dialog = null
        review = null
    }
    return status?.let {
        val hasSession = appState.remoteSigners.session(account) != null
        RemoteSignerAccountActions(it, approvalOpened, hasSession) { action ->
            when (action) {
                RemoteSignerAccountAction.Retry -> appState.reconnectRemoteSigner(account)
                // I've finished appears only after Android accepted the page, so an unseen check is never confirmed.
                RemoteSignerAccountAction.OpenApproval ->
                    approvalOpened = it.authUrl?.let { url -> openApprovalPage(context, url) } == true
                RemoteSignerAccountAction.FinishApproval ->
                    it.requestId?.let { request -> appState.completeRemoteSignerApproval(account, request) }
                RemoteSignerAccountAction.ReviewPermissions -> review = appState.reviewRemoteSignerPermissions(account)
                RemoteSignerAccountAction.PairAgain, RemoteSignerAccountAction.Disconnect -> dialog = action
            }
        }
    }
}

/** Disconnect confirmation, renewed permission review and the re-pair flow for one account. */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerAccountDialogs(
    appState: WhiteNoiseAppState,
    account: String,
    dialog: RemoteSignerAccountAction?,
    review: Nip46PermissionsFfi?,
    onDismiss: () -> Unit,
) {
    if (dialog == RemoteSignerAccountAction.Disconnect) {
        ConfirmDialog(
            title = stringResource(R.string.remote_signer_disconnect_title),
            message = stringResource(R.string.remote_signer_disconnect_body),
            confirmLabel = stringResource(R.string.remote_signer_disconnect_confirm),
            onConfirm = {
                onDismiss()
                appState.disconnectRemoteSigner(account)
            },
            onDismiss = onDismiss,
            destructive = true,
        )
    }
    if (dialog == RemoteSignerAccountAction.PairAgain) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
        ) {
            RemoteSignerFlow(appState, onClose = onDismiss, repairAccountHex = account)
        }
    }
    review?.let { permissions ->
        WhiteNoiseAlertDialog(
            ownerKey = permissions,
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.remote_signer_review_title)) },
            text = { RemoteSignerPermissionReview(permissions) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismiss()
                        appState.launchMutation { appState.approveRemoteSignerPermissions(account, permissions) }
                    },
                    modifier = Modifier.testTag("remote_signer.account.approve"),
                ) { Text(stringResource(R.string.remote_signer_review_approve)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Opens only an https approval page in the user's browser; returns whether Android accepted it. */
private fun openApprovalPage(
    context: Context,
    url: String,
): Boolean {
    if (remoteSignerApprovalHost(url) == null) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The status line; a pending browser check names the page's host before anything can be opened. */
@Composable
private fun remoteSignerStatusText(actions: RemoteSignerAccountActions): String {
    val host = actions.approvalHost
    val approval = actions.status.health == RemoteSignerHealth.ApprovalNeeded
    return if (approval && host != null) {
        stringResource(R.string.remote_signer_status_approval_host, host)
    } else if (approval) {
        stringResource(R.string.remote_signer_status_approval_unsupported)
    } else {
        stringResource(remoteSignerStatusRes(actions.status.health))
    }
}

/** Replaces the private-key section for a remote-signer account: who holds the key, its status and recovery. */
internal fun LazyListScope.remoteSignerAccountItems(actions: RemoteSignerAccountActions) {
    val health = actions.status.health
    item(key = "remote_signer.status") {
        SettingsCallout(
            title = stringResource(R.string.remote_signer_signed_in),
            text = stringResource(R.string.remote_signer_owns_key) + "\n" + remoteSignerStatusText(actions),
            // A signer that is offline or reconnecting is routine; only an ended pairing reads as an error.
            isError = health == RemoteSignerHealth.PairingEnded,
            modifier = Modifier.testTag("profile_keys.remote_signer_info"),
        )
    }
    item(key = "remote_signer.actions") {
        SettingsGroup {
            actions.available.forEach { action ->
                row(action.tag) { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(action.titleRes),
                        onClick = { actions.onAction(action) },
                        destructive = action == RemoteSignerAccountAction.Disconnect,
                        leading = null,
                        modifier = Modifier.testTag(action.tag),
                    )
                }
            }
        }
    }
}
