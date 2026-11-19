@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import java.time.format.FormatStyle

internal const val LINKED_DEVICES_LIST_TAG = "linked_devices.list"
internal const val LINKED_DEVICES_ERROR_TAG = "linked_devices.error"
internal const val LINKED_DEVICES_RETRY_TAG = "linked_devices.retry"
internal const val LINKED_DEVICES_EMPTY_TAG = "linked_devices.empty"
internal const val LINKED_DEVICES_LOADING_TAG = "linked_devices.loading"
internal const val LINKED_DEVICES_LINK_THIS_TAG = "linked_devices.link_this_device"
internal const val LINKED_DEVICES_EXTERNAL_SIGNER_TAG = "linked_devices.external_signer"
internal const val LINKED_DEVICES_MOVE_FINISH_LINKING_TAG = "linked_devices.move.finish_linking"

/**
 * The roster screen for [state]. This device leads, other devices follow, and devices whose removal the device
 * group has not taken yet are listed apart. A failed read is announced above whatever rows were already read, with
 * a retry, and is never shown as an empty list. A row opens that device's page. Above the rows come the open
 * security warning and this device's standing notices; below them the chat counts, any moves MDK reports, then
 * the linking actions: Link this device while it has no linked sibling (or to return to a link under way), Move
 * this device once it has, and what to do on another device to add it.
 * [showLoading] false holds the loading note back while a first read is expected at once.
 */
@Composable
internal fun LinkedDevicesContent(
    state: LinkedDeviceRosterState,
    onBack: () -> Unit,
    linking: LinkedDevicesLinking = LinkedDevicesLinking(),
    actions: LinkedDevicesActions = LinkedDevicesActions(),
    health: LinkedDevicesHealth = LinkedDevicesHealth(),
    showLoading: Boolean = true,
) {
    SettingsScaffold(title = stringResource(R.string.linked_devices), onBack = onBack) {
        SettingsList(modifier = Modifier.testTag(LINKED_DEVICES_LIST_TAG)) {
            item(key = "detail") { SettingsExplainer(stringResource(R.string.linked_devices_detail)) }
            compromiseWarning(health.review, actions.review.onDismissWarning)
            linkedDevicesNotices(health, actions.onRepair)
            if (state.readFailed) linkedDevicesFailure(state.refreshing, actions.onRetry)
            linkedDevicesRoster(state, showLoading, actions.onOpenDevice)
            linkedDevicesChatCounts(health.counts)
            linkedDevicesMoves(linking, actions.onDismissMoveSibling)
            linkedDevicesLinking(state, linking, actions.onLinkThisDevice)
            if (linking.availability == DeviceLinkAvailability.Available) signInReview(health.review, actions.review)
            linkedDevicesMoveAction(state, linking, actions.onLinkThisDevice)
        }
    }
}

/** The failure notice and its retry, placed above the rows that were already read. */
private fun LazyListScope.linkedDevicesFailure(
    refreshing: Boolean,
    onRetry: () -> Unit,
) {
    item(key = "error") {
        SettingsCallout(
            text = stringResource(R.string.linked_devices_load_failed),
            isError = true,
            modifier = Modifier.testTag(LINKED_DEVICES_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    item(key = "retry") {
        SettingsGroup {
            row("retry") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.retry),
                    onClick = onRetry,
                    enabled = !refreshing,
                    modifier = Modifier.testTag(LINKED_DEVICES_RETRY_TAG),
                    leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                )
            }
        }
    }
}

/** The loading note before the first read, the empty note, or the grouped device rows. */
private fun LazyListScope.linkedDevicesRoster(
    state: LinkedDeviceRosterState,
    showLoading: Boolean,
    onOpenDevice: (String) -> Unit,
) {
    val roster = state.roster
    when {
        roster == null && !state.readFailed && !showLoading -> Unit
        roster == null && !state.readFailed ->
            item(key = "loading") {
                SettingsCallout(
                    stringResource(R.string.linked_devices_loading),
                    Modifier.testTag(LINKED_DEVICES_LOADING_TAG),
                )
            }
        roster == null -> Unit
        roster.isEmpty ->
            item(key = "empty") {
                SettingsCallout(
                    stringResource(R.string.linked_devices_empty),
                    Modifier.testTag(LINKED_DEVICES_EMPTY_TAG),
                )
            }
        else -> {
            roster.thisDevice?.let { device ->
                linkedDeviceSection(R.string.linked_devices_this_device, "this", listOf(device), onOpenDevice)
            }
            linkedDeviceSection(R.string.linked_devices_other_devices, "others", roster.others, onOpenDevice)
            linkedDeviceSection(R.string.linked_devices_removing, "removing", roster.removing, onOpenDevice)
            if (!roster.hasOtherDevices) {
                item(key = "no-others") {
                    SettingsCallout(
                        stringResource(R.string.linked_devices_no_others),
                        Modifier.testTag(LINKED_DEVICES_EMPTY_TAG),
                    )
                }
            }
        }
    }
}

/** One headed group of device rows; nothing is emitted for an empty group. */
private fun LazyListScope.linkedDeviceSection(
    title: Int,
    key: String,
    devices: List<LinkedDeviceRow>,
    onOpenDevice: (String) -> Unit,
) {
    if (devices.isEmpty()) return
    item(key = "section.$key") { SettingsSection(stringResource(title)) }
    item(key = "group.$key") {
        SettingsGroup(modifier = Modifier.testTag("linked_devices.group.$key")) {
            devices.forEach { device ->
                row(device.slotIdHex) { context ->
                    SettingsLink(
                        context = context,
                        title = device.label.ifBlank { stringResource(R.string.linked_devices_unnamed) },
                        onClick = { onOpenDevice(device.slotIdHex) },
                        subtitle = linkedDeviceSummary(device).joinToString("\n"),
                        modifier = Modifier.testTag("linked_devices.device.${device.slotIdHex}"),
                        explanation = stringResource(R.string.explanation_linked_device_row),
                        leading = { SettingsLeadingIcon(R.drawable.ic_device) },
                    )
                }
            }
        }
    }
}

/**
 * The lines under a device's name: its client and platform, when it was linked, the last heartbeat another device
 * saw (worded as past activity, never as being online), and any state MDK reports for it.
 */
@Composable
internal fun linkedDeviceSummary(device: LinkedDeviceRow): List<String> =
    buildList {
        listOf(device.client, device.platform)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .takeIf(String::isNotEmpty)
            ?.let(::add)
        deviceDate(device.linkedAtSeconds, withTime = false)?.let {
            add(stringResource(R.string.linked_devices_linked_on, it))
        }
        device.linkedByLabel?.let { linker ->
            val name = linker.ifBlank { stringResource(R.string.linked_devices_unnamed) }
            add(stringResource(R.string.linked_devices_linked_by, name))
        }
        if (!device.isThisDevice) {
            add(
                device.lastActiveAtSeconds
                    ?.let { deviceDate(it, withTime = true) }
                    ?.let { stringResource(R.string.linked_devices_last_active, it) }
                    ?: stringResource(R.string.linked_devices_no_activity_seen),
            )
        }
        linkedDeviceStateLabel(device)?.let { add(stringResource(it)) }
    }

/**
 * The state line for [device], or null for an active device. A pending removal outranks the roster's status, and
 * the roster's status outranks this device's own judgement that a sibling has been quiet for 30 days.
 */
internal fun linkedDeviceStateLabel(device: LinkedDeviceRow): Int? =
    when {
        device.removalPending -> R.string.linked_devices_removal_pending
        device.status == LinkedDeviceStatusFfi.INACTIVE -> R.string.linked_devices_status_inactive
        device.status == LinkedDeviceStatusFfi.LEAVING -> R.string.linked_devices_status_leaving
        device.status == LinkedDeviceStatusFfi.MOVED -> R.string.linked_devices_status_moved
        device.inactive -> R.string.linked_devices_judged_inactive
        else -> null
    }

/** [epochSeconds] as a local medium date, with a short time when asked; null when MDK gave no usable stamp. */
private fun deviceDate(
    epochSeconds: Long,
    withTime: Boolean,
): String? =
    IdentityFormatter
        .dateTime(
            epochSeconds.coerceAtLeast(0).toULong(),
            dateStyle = FormatStyle.MEDIUM,
            timeStyle = FormatStyle.SHORT.takeIf { withTime },
        ).takeIf(String::isNotEmpty)
