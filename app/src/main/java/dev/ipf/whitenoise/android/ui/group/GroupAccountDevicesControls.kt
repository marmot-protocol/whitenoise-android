@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.AccountDevicesActivationFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.accountDevicesActivationReason
import dev.ipf.whitenoise.android.state.accountDevicesRevision
import dev.ipf.whitenoise.android.state.readAccountDevicesActivation
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupScope
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsValue

internal const val GROUP_ACCOUNT_DEVICES_ENABLE_TAG = "group.account_devices.enable"
internal const val GROUP_ACCOUNT_DEVICES_STATUS_TAG = "group.account_devices.status"

/** Follow the current group’s eligibility and withdraw its confirmation if the group loses the capability. */
@Composable
internal fun observeGroupAccountDevicesActivation(
    controller: ConversationController,
    onEligibilityLost: () -> Unit,
): AccountDevicesActivationFfi? {
    val activation =
        rememberAccountDevicesActivation(
            owner = controller,
            enabled = linkedDevicesAvailable() && controller.isSelfMember,
            revision = controller.accountDevicesRevision(),
            read = controller::readAccountDevicesActivation,
        )
    val eligibilityLost = activation != null && activation != AccountDevicesActivationFfi.ELIGIBLE
    LaunchedEffect(eligibilityLost, controller.isSelfMember) {
        if (eligibilityLost || !controller.isSelfMember) onEligibilityLost()
    }
    return activation
}

/**
 * MDK's answer to whether this group can take linked devices, read when the details open and again whenever
 * [revision] changes, which the caller advances after any group change or refusal. Null until read, and null when
 * linked devices are not part of this build.
 */
@Composable
internal fun rememberAccountDevicesActivation(
    owner: Any,
    enabled: Boolean,
    revision: Any,
    read: suspend () -> AccountDevicesActivationFfi?,
): AccountDevicesActivationFfi? {
    var activation by remember(owner, enabled, revision) { mutableStateOf<AccountDevicesActivationFfi?>(null) }
    LaunchedEffect(owner, enabled, revision) {
        activation = if (enabled) read() else null
    }
    return activation
}

/**
 * The linked-devices row of a group's details. An eligible group offers the action behind
 * [onEnableRequested]'s confirmation. A group that already has it, or cannot take it, says so on a row with no
 * action. A member who is not an admin sees no row, because MDK tells a non-admin nothing more.
 */
internal fun SettingsGroupScope.groupAccountDevicesRows(
    activation: AccountDevicesActivationFfi?,
    enabled: Boolean,
    onEnableRequested: () -> Unit,
) {
    when (activation) {
        null, AccountDevicesActivationFfi.NOT_ADMIN -> Unit
        AccountDevicesActivationFfi.ELIGIBLE ->
            row("enable_account_devices") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.group_account_devices_enable),
                    explanation = stringResource(R.string.group_account_devices_enable_detail),
                    onClick = onEnableRequested,
                    enabled = enabled,
                    modifier = Modifier.testTag(GROUP_ACCOUNT_DEVICES_ENABLE_TAG),
                    leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                )
            }
        else ->
            row("account_devices_status") { context ->
                val reason = accountDevicesActivationReason(activation)
                SettingsValue(
                    context = context,
                    title = stringResource(R.string.group_account_devices_title),
                    value = stringResource(reason ?: R.string.group_account_devices_enabled),
                    modifier = Modifier.testTag(GROUP_ACCOUNT_DEVICES_STATUS_TAG),
                    explanation = stringResource(R.string.group_account_devices_enable_detail),
                    leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                )
            }
    }
}

/** Says that the change cannot be undone by anyone before the admin commits to it. */
@Composable
internal fun GroupAccountDevicesConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    identity: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    ConfirmDialog(
        title = stringResource(R.string.group_account_devices_confirm_title),
        message = stringResource(R.string.group_account_devices_confirm_message),
        confirmLabel = stringResource(R.string.group_account_devices_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        identity = identity,
        confirmEnabled = enabled,
    )
}
