@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.linkedDeviceRoster
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog

internal const val INVITE_DECLINE_ALL_DEVICES_TAG = "invite.decline.allDevices.dialog"
internal const val INVITE_DECLINE_ALL_DEVICES_CONFIRM_TAG = "invite.decline.allDevices.confirm"
internal const val INVITE_DECLINE_ALL_DEVICES_CANCEL_TAG = "invite.decline.allDevices.cancel"

/**
 * Whether declining here also takes the account's other devices out of the chat. MDK shares a decline with every
 * device of the account, so it does whenever [devices] lists one besides this device. A roster that could not be
 * read is null and asks nothing, because the question would then claim devices nobody confirmed.
 */
internal fun declineReachesOtherDevices(devices: List<LinkedDeviceFfi>?): Boolean =
    devices != null && linkedDeviceRoster(devices).hasOtherDevices

/**
 * Hands [content] the Decline action for the invitation in view. With no other linked device it declines at once.
 * With one, it first asks, since the decline leaves the chat on all of them. The roster is read when Decline is
 * tapped, so a device linked or removed while the invitation was open is counted. [onDeclined] runs after a
 * decline that went through.
 */
@Composable
internal fun InviteDeclineGate(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    onDeclined: () -> Unit,
    content: @Composable (onDecline: () -> Unit) -> Unit,
) {
    var askedFor by remember { mutableStateOf<String?>(null) }
    val groupIdHex = controller.group.groupIdHex
    content {
        appState.launchMutation {
            val devices =
                controller.conversationAccountRef?.let { account ->
                    runCatchingCancellable { appState.marmotIo { linkedDevices(account) } }.getOrNull()
                }
            if (declineReachesOtherDevices(devices)) {
                askedFor = groupIdHex
            } else if (controller.declineInvite()) {
                onDeclined()
            }
        }
    }
    // The question belongs to one invitation, and goes away with it when another device answers first.
    if (askedFor == groupIdHex && controller.group.pendingConfirmation) {
        InviteDeclineAllDevicesDialog(
            onConfirm = {
                askedFor = null
                appState.launchMutation { if (controller.declineInvite()) onDeclined() }
            },
            onDismiss = { askedFor = null },
        )
    }
}

/** The question itself: what declining does on the account's other devices, with Decline as its confirming action. */
@Composable
internal fun InviteDeclineAllDevicesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(INVITE_DECLINE_ALL_DEVICES_TAG),
        title = { Text(stringResource(R.string.invite_decline_all_devices_title)) },
        text = { Text(stringResource(R.string.invite_decline_all_devices_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(INVITE_DECLINE_ALL_DEVICES_CONFIRM_TAG),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.decline)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(INVITE_DECLINE_ALL_DEVICES_CANCEL_TAG)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
