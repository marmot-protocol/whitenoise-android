@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.DeviceGroupMoveStateFfi
import dev.ipf.marmotkit.DeviceGroupMoveViewFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkAttempt
import dev.ipf.whitenoise.android.state.LinkedDeviceChatCounts
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSection

/** Whether this profile can start or approve links here, and if not, why the actions are withheld. */
internal enum class DeviceLinkAvailability {
    Available,
    ExternalSigner,
}

/**
 * What the roster screen needs beyond the rows: whether linking is offered, how far this device's link is, the
 * moves MDK reports for it, whether a device this one approved is still joining and whether a command is running.
 */
internal data class LinkedDevicesLinking(
    val availability: DeviceLinkAvailability = DeviceLinkAvailability.Available,
    val newDevice: NewDeviceLinkStateFfi? = null,
    val moves: List<DeviceGroupMoveViewFfi> = emptyList(),
    val approvedLinkJoining: Boolean = false,
    val busy: Boolean = false,
)

/** What the roster screen can ask its owner to do. */
internal data class LinkedDevicesActions(
    val onRetry: () -> Unit = {},
    val onLinkThisDevice: () -> Unit = {},
    val onDismissMoveSibling: (moveId: String, slotIdHex: String) -> Unit = { _, _ -> },
    val onOpenDevice: (slotIdHex: String) -> Unit = {},
    val onRepair: () -> Unit = {},
    val review: SignInReviewActions = SignInReviewActions(),
)

/**
 * What MDK reports about this device's standing beside the roster: the chat counts, whether its KeyPackage refresh
 * is overdue, whether it can no longer keep up with its device group, and the sign-ins and warning under review.
 */
internal data class LinkedDevicesHealth(
    val counts: LinkedDeviceChatCounts = LinkedDeviceChatCounts(),
    val keyPackageRefreshOverdue: Boolean = false,
    val stalled: Boolean = false,
    val repairNeedsConfirmation: Boolean = false,
    val busy: Boolean = false,
    val review: SignInReviewState = SignInReviewState(),
    val missedLinks: List<DeviceLinkAttempt> = emptyList(),
)

/** Whether a move is still under way. MDK accepts no further move or link request while one is. */
internal fun DeviceGroupMoveViewFfi.pending(): Boolean {
    val waiting = state == DeviceGroupMoveStateFfi.WAITING_FOR_WELCOME
    return waiting || state == DeviceGroupMoveStateFfi.JOINING
}

/**
 * The line for a move's [state]. Waiting and joining are never worded as moved: only MDK's Made says so, and a
 * failed move says the earlier trust still stands.
 */
internal fun deviceMoveStateLabel(state: DeviceGroupMoveStateFfi): Int =
    when (state) {
        DeviceGroupMoveStateFfi.WAITING_FOR_WELCOME -> R.string.device_move_waiting
        DeviceGroupMoveStateFfi.JOINING -> R.string.device_move_joining
        DeviceGroupMoveStateFfi.MADE -> R.string.device_move_made
        DeviceGroupMoveStateFfi.FAILED -> R.string.device_move_failed
    }

/** Whether this device's own link is still under way, so the roster offers to return to it. */
internal fun NewDeviceLinkStateFfi?.inProgress(): Boolean {
    val settled = this is NewDeviceLinkStateFfi.Linked || this is NewDeviceLinkStateFfi.Ended
    return this != null && !settled
}

/**
 * This device's moves between sets of the account's devices, as MDK reports them. Each shows its state, a note when
 * devices rejected earlier were already linked in the destination, and one reminder per recognized device left
 * behind. Dismissing a reminder only hides it; the reminders are guidance and never decide whom to trust.
 */
internal fun LazyListScope.linkedDevicesMoves(
    linking: LinkedDevicesLinking,
    onDismissSibling: (moveId: String, slotIdHex: String) -> Unit,
) {
    if (linking.moves.isEmpty()) return
    item(key = "section.moves") { SettingsSection(stringResource(R.string.device_move_section)) }
    linking.moves.forEach { move ->
        item(key = "move.${move.moveId}") {
            SettingsCallout(
                text = stringResource(deviceMoveStateLabel(move.state)),
                isError = move.state == DeviceGroupMoveStateFfi.FAILED,
                modifier = Modifier.testTag("linked_devices.move.${move.moveId}"),
            )
        }
        if (move.overriddenRejectionSlots.isNotEmpty()) {
            item(key = "move.${move.moveId}.overridden") {
                SettingsExplainer(stringResource(R.string.device_move_overridden))
            }
        }
        val reminders = move.oldSiblings.filter { it.questionSuppressed }
        if (reminders.isNotEmpty()) {
            item(key = "move.${move.moveId}.siblings") {
                SettingsGroup {
                    reminders.forEach { sibling ->
                        row(sibling.slotIdHex) { context ->
                            SettingsAction(
                                context = context,
                                title = stringResource(R.string.device_move_dismiss),
                                subtitle = deviceMoveSiblingReminder(sibling.label),
                                onClick = { onDismissSibling(move.moveId, sibling.slotIdHex) },
                                enabled = !linking.busy,
                                modifier = Modifier.testTag("linked_devices.move.dismiss.${sibling.slotIdHex}"),
                                explanation = stringResource(R.string.device_move_dismiss_detail),
                                leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The reminder that a recognized device stayed in the earlier set, named when MDK knows its label. */
@Composable
private fun deviceMoveSiblingReminder(label: String): String =
    if (label.isBlank()) {
        stringResource(R.string.device_move_old_sibling_unnamed)
    } else {
        stringResource(R.string.device_move_old_sibling, label)
    }

/**
 * The linking section. An account that signs through an external signer app is told linking is not available yet.
 * Otherwise Link this device shows once the roster is read and lists no sibling, or while this device's link is
 * under way, and the steps for adding another device follow.
 */
internal fun LazyListScope.linkedDevicesLinking(
    state: LinkedDeviceRosterState,
    linking: LinkedDevicesLinking,
    onLinkThisDevice: () -> Unit,
) {
    if (linking.availability == DeviceLinkAvailability.ExternalSigner) {
        item(key = "external-signer") {
            SettingsCallout(
                stringResource(R.string.link_device_external_signer),
                Modifier.testTag(LINKED_DEVICES_EXTERNAL_SIGNER_TAG),
            )
        }
        return
    }
    val underWay = linking.newDevice.inProgress()
    // Nothing is offered before the roster is read, except the way back into a link already under way.
    val unlinked = state.roster?.hasOtherDevices == false
    if (underWay || unlinked) {
        val detail = if (underWay) R.string.link_device_in_progress else R.string.link_device_this_detail
        item(key = "link-this-device") {
            LinkThisDeviceRow(R.string.link_device_this_title, detail, !linking.busy, onLinkThisDevice)
        }
    }
    item(key = "section.add") { SettingsSection(stringResource(R.string.link_device_add_title)) }
    item(key = "add-steps") { SettingsExplainer(stringResource(R.string.link_device_add_steps)) }
}

/**
 * Move this device, last on the screen under Advanced because few people need it. A linked device starts a move
 * with the same request as a link, and MDK takes no new one while a move or a link is still under way. While a
 * device this one approved is still joining, a note says to let it finish, because moving can fail that link.
 */
internal fun LazyListScope.linkedDevicesMoveAction(
    state: LinkedDeviceRosterState,
    linking: LinkedDevicesLinking,
    onMoveThisDevice: () -> Unit,
) {
    val available = linking.availability == DeviceLinkAvailability.Available && !linking.newDevice.inProgress()
    val linked = state.roster?.hasOtherDevices == true
    if (!available || !linked || linking.moves.any { it.pending() }) return
    item(key = "section.advanced") { SettingsSection(stringResource(R.string.device_move_advanced)) }
    item(key = "move-this-device") {
        LinkThisDeviceRow(R.string.device_move_title, R.string.device_move_detail, !linking.busy, onMoveThisDevice)
    }
    if (linking.approvedLinkJoining) {
        item(key = "move-finish-linking") {
            SettingsCallout(
                stringResource(R.string.device_move_finish_linking),
                Modifier.testTag(LINKED_DEVICES_MOVE_FINISH_LINKING_TAG),
            )
        }
    }
}

/** The row that starts this device's link or move, saying under its [title] what it does or how far it is. */
@Composable
private fun LinkThisDeviceRow(
    title: Int,
    detail: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SettingsGroup {
        row("link_this_device") { context ->
            SettingsLink(
                context = context,
                title = stringResource(title),
                onClick = onClick,
                modifier = Modifier.testTag(LINKED_DEVICES_LINK_THIS_TAG),
                explanation = stringResource(detail),
                subtitle = stringResource(detail),
                enabled = enabled,
                leading = { SettingsLeadingIcon(R.drawable.ic_link) },
            )
        }
    }
}
