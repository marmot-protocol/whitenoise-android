@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LinkedDeviceChatCounts
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsValue

internal const val LINKED_DEVICES_STALLED_TAG = "linked_devices.stalled"
internal const val LINKED_DEVICES_REPAIR_TAG = "linked_devices.repair"
internal const val LINKED_DEVICES_REPAIR_CONFIRM_TAG = "linked_devices.repair.confirm"
internal const val LINKED_DEVICES_OVERDUE_TAG = "linked_devices.key_refresh_overdue"
internal const val LINKED_DEVICES_CLOSED_CHATS_TAG = "linked_devices.closed_chats"
internal const val LINKED_DEVICES_LEFTOVER_CHATS_TAG = "linked_devices.leftover_chats"

/**
 * The two standing notices, kept apart because they need different things from the user. A device that can no
 * longer keep up with its device group is offered the repair. A device whose KeyPackage refresh is overdue is only
 * told, since nothing it can press fixes that.
 */
internal fun LazyListScope.linkedDevicesNotices(
    health: LinkedDevicesHealth,
    onRepair: () -> Unit,
) {
    if (health.stalled || health.repairNeedsConfirmation) {
        val detail =
            if (health.repairNeedsConfirmation) R.string.device_repair_confirm_again else R.string.devices_stalled
        item(key = "stalled") {
            SettingsCallout(
                text = stringResource(detail),
                isError = true,
                modifier = Modifier.testTag(LINKED_DEVICES_STALLED_TAG),
            )
        }
        item(key = "repair") {
            SettingsGroup {
                row("repair") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.devices_repair),
                        onClick = onRepair,
                        enabled = !health.busy,
                        modifier = Modifier.testTag(LINKED_DEVICES_REPAIR_TAG),
                        explanation = stringResource(R.string.devices_repair_confirm),
                        leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                    )
                }
            }
        }
    }
    if (health.keyPackageRefreshOverdue) {
        item(key = "overdue") {
            SettingsCallout(
                text = stringResource(R.string.devices_key_refresh_overdue),
                modifier = Modifier.testTag(LINKED_DEVICES_OVERDUE_TAG),
            )
        }
    }
}

/**
 * The chats MDK counts as closed to linked devices or still holding a removed device. A count is shown only when
 * MDK reported one above zero: zero does not prove every device reached every chat, so it is not shown as good news.
 */
internal fun LazyListScope.linkedDevicesChatCounts(counts: LinkedDeviceChatCounts) {
    val closed = counts.closedToLinkedDevices?.takeIf { it > 0 }
    val leftover = counts.holdingRemovedDevice?.takeIf { it > 0 }
    if (closed == null && leftover == null) return
    item(key = "chat-counts") {
        SettingsGroup {
            if (closed != null) {
                row("closed_chats") { context ->
                    SettingsValue(
                        context = context,
                        title = stringResource(R.string.devices_closed_chats, closed),
                        value = stringResource(R.string.devices_closed_chats_detail),
                        modifier = Modifier.testTag(LINKED_DEVICES_CLOSED_CHATS_TAG),
                        leading = { SettingsLeadingIcon(R.drawable.ic_settings_chat_bubble_outline) },
                    )
                }
            }
            if (leftover != null) {
                row("leftover_chats") { context ->
                    SettingsValue(
                        context = context,
                        title = stringResource(R.string.devices_leftover_chats, leftover),
                        value = stringResource(R.string.devices_leftover_chats_detail),
                        modifier = Modifier.testTag(LINKED_DEVICES_LEFTOVER_CHATS_TAG),
                        leading = { SettingsLeadingIcon(R.drawable.ic_person_remove) },
                    )
                }
            }
        }
    }
}

/**
 * Explains the repair before it happens: ordinary data stays, the old link is given up for good, and cancelling
 * the new link afterwards does not bring it back.
 */
@Composable
internal fun RepairDeviceLinkDialog(
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_repair)) },
        text = { Text(stringResource(R.string.devices_repair_confirm)) },
        confirmButton = {
            TextButton(onClick = onRepair, modifier = Modifier.testTag(LINKED_DEVICES_REPAIR_CONFIRM_TAG)) {
                Text(stringResource(R.string.devices_repair), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
