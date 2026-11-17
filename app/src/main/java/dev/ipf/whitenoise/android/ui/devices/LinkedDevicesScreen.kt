@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkNative
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.settings.SettingsValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.FormatStyle

internal const val LINKED_DEVICES_LIST_TAG = "linked_devices.list"
internal const val LINKED_DEVICES_ERROR_TAG = "linked_devices.error"
internal const val LINKED_DEVICES_RETRY_TAG = "linked_devices.retry"
internal const val LINKED_DEVICES_EMPTY_TAG = "linked_devices.empty"
internal const val LINKED_DEVICES_LOADING_TAG = "linked_devices.loading"

private const val DEVICE_LINK_SUBSCRIPTION_RETRY_MS = 2_000L

/** MDK's linked-device reads through the app's runtime, on its native IO dispatcher. */
private class RuntimeDeviceLink(
    private val appState: WhiteNoiseAppState,
) : DeviceLinkNative {
    /** Reads the link snapshot from the current runtime. */
    override suspend fun deviceLinkSnapshot(accountRef: String) = appState.marmotIo { deviceLinkSnapshot(accountRef) }

    /** Reads the roster from the current runtime. */
    override suspend fun linkedDevices(accountRef: String) = appState.marmotIo { linkedDevices(accountRef) }
}

/**
 * Binds the active account's linked-device state to this composition. A new controller is made for every account
 * or runtime change and the old one is retired, so no result crosses accounts. While the screen is started it
 * reads once and then re-reads on MDK's change events; returning to the foreground or reopening a closed stream
 * reads again.
 */
@Composable
internal fun rememberDeviceLinkController(appState: WhiteNoiseAppState): DeviceLinkController? {
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller =
        remember(accountRef, runtimeGeneration) {
            accountRef?.let { account ->
                DeviceLinkController(
                    accountRef = account,
                    native = RuntimeDeviceLink(appState),
                    owns = { appState.activeAccountRef == account && appState.runtimeGeneration == runtimeGeneration },
                )
            }
        }
    DisposableEffect(controller) { onDispose { controller?.retire() } }
    LaunchedEffect(controller, lifecycle) {
        val owner = controller ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
                try {
                    if (subscription == null) {
                        owner.refreshRoster()
                        owner.refreshLink()
                    } else {
                        owner.observe { withContext(Dispatchers.IO) { subscription.next() } }
                    }
                } finally {
                    subscription?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.destroy() } } }
                }
                // A closed stream or a failed subscription must not leave the screen without updates.
                delay(DEVICE_LINK_SUBSCRIPTION_RETRY_MS)
            }
        }
    }
    return controller
}

/** Settings → Linked devices: the active account's device roster as MDK lists it. */
@Composable
internal fun LinkedDevicesScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val controller = rememberDeviceLinkController(appState)
    val scope = rememberCoroutineScope()
    LinkedDevicesContent(
        state = controller?.roster ?: LinkedDeviceRosterState(),
        onBack = onBack,
        onRetry = { controller?.let { scope.launch { it.refreshRoster() } } },
    )
}

/**
 * The roster screen for [state]. This device leads, other devices follow, and devices whose removal the device
 * group has not taken yet are listed apart. A failed read is announced above whatever rows were already read, with
 * a retry, and is never shown as an empty list.
 */
@Composable
internal fun LinkedDevicesContent(
    state: LinkedDeviceRosterState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.linked_devices), onBack = onBack) {
        SettingsList(modifier = Modifier.testTag(LINKED_DEVICES_LIST_TAG)) {
            item(key = "detail") { SettingsExplainer(stringResource(R.string.linked_devices_detail)) }
            if (state.readFailed) linkedDevicesFailure(state.refreshing, onRetry)
            linkedDevicesRoster(state)
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
private fun LazyListScope.linkedDevicesRoster(state: LinkedDeviceRosterState) {
    val roster = state.roster
    when {
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
                linkedDeviceSection(R.string.linked_devices_this_device, "this", listOf(device))
            }
            linkedDeviceSection(R.string.linked_devices_other_devices, "others", roster.others)
            linkedDeviceSection(R.string.linked_devices_removing, "removing", roster.removing)
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
) {
    if (devices.isEmpty()) return
    item(key = "section.$key") { SettingsSection(stringResource(title)) }
    item(key = "group.$key") {
        SettingsGroup(modifier = Modifier.testTag("linked_devices.group.$key")) {
            devices.forEach { device ->
                row(device.slotIdHex) { context ->
                    SettingsValue(
                        context = context,
                        title = device.label.ifBlank { stringResource(R.string.linked_devices_unnamed) },
                        value = linkedDeviceSummary(device).joinToString("\n"),
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
