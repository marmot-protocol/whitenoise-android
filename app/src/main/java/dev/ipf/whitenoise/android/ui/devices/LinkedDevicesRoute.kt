@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cancel
import dev.ipf.whitenoise.android.state.confirmMatch
import dev.ipf.whitenoise.android.state.dismissMoveSibling
import dev.ipf.whitenoise.android.state.start
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which page of Linked devices is showing in place of the roster. Saved by slot so rotation keeps the page. */
private data class LinkedDevicesPage(
    val linking: Boolean = false,
    val deviceSlot: String? = null,
    val reviewingSlot: String? = null,
    val repairing: Boolean = false,
)

/**
 * Settings → Linked devices for the active account: the roster, a device's page, this device's own link, and the
 * dialogs for repair and sign-in review. With no active account there is nothing to read, so the roster shows its
 * loading state.
 */
@Composable
internal fun LinkedDevicesScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val controller = rememberDeviceLinkController(appState)
    val management = rememberDeviceManagement(appState)
    if (controller == null || management == null) {
        LinkedDevicesContent(LinkedDeviceRosterState(), onBack)
    } else {
        LinkedDevicesPages(appState, controller, management, onBack)
    }
}

/**
 * The pages of Linked devices for one account on one runtime. The open page is keyed to that pairing, so nothing
 * opened for one profile reopens over another. Leaving the link step with Back only closes it; only Cancel cancels.
 */
@Composable
private fun LinkedDevicesPages(
    appState: WhiteNoiseAppState,
    controller: DeviceLinkController,
    management: DeviceManagement,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = controller.accountRef to appState.runtimeGeneration
    var linking by rememberSaveable(key) { mutableStateOf(false) }
    var deviceSlot by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var reviewingSlot by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var repairing by rememberSaveable(key) { mutableStateOf(false) }
    DeviceLinkFailureEffect(appState, controller)
    DeviceManagementFailureEffect(appState, management)
    val openDevice = deviceSlot?.let { controller.roster.roster?.device(it) }
    when {
        linking -> LinkThisDeviceRoute(controller, onClose = { linking = false })
        openDevice != null -> LinkedDeviceDetailRoute(openDevice, management, scope, onClose = { deviceSlot = null })
        else ->
            LinkedDevicesContent(
                state = controller.roster,
                onBack = onBack,
                linking = linkedDevicesLinking(appState, controller, management),
                actions =
                    linkedDevicesActions(scope, controller, management) { page ->
                        linking = page.linking
                        deviceSlot = page.deviceSlot
                        reviewingSlot = page.reviewingSlot
                        repairing = page.repairing
                    },
                health = linkedDevicesHealth(controller, management),
            )
    }
    LinkedDevicesDialogs(
        management = management,
        reviewingSlot = reviewingSlot,
        repairing = repairing,
        onRepaired = { scope.launch { controller.refreshLink() } },
        onClose = {
            reviewingSlot = null
            repairing = false
        },
    )
}

/**
 * The sign-in review and the repair explanation over the roster. Each closes before its answer is sent, so a slow
 * answer never leaves a dialog that could be answered twice.
 */
@Composable
private fun LinkedDevicesDialogs(
    management: DeviceManagement,
    reviewingSlot: String?,
    repairing: Boolean,
    onRepaired: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reviewing =
        management.signIns.list.signIns
            .firstOrNull { it.slotIdHex == reviewingSlot }
    if (reviewing != null) {
        SignInReviewDialog(
            signIn = reviewing,
            onAnswer = { response ->
                onClose()
                scope.launch { management.signIns.answer(reviewing.slotIdHex, response) }
            },
            onClose = onClose,
        )
    }
    if (repairing) {
        RepairDeviceLinkDialog(
            onRepair = {
                onClose()
                scope.launch { if (management.devices.resetForRepair() != null) onRepaired() }
            },
            onDismiss = onClose,
        )
    }
}

/** What the roster shows about linking for the active account. */
private fun linkedDevicesLinking(
    appState: WhiteNoiseAppState,
    controller: DeviceLinkController,
    management: DeviceManagement,
): LinkedDevicesLinking {
    val externalSigner = appState.activeAccount?.localSigning != true
    return LinkedDevicesLinking(
        availability = if (externalSigner) DeviceLinkAvailability.ExternalSigner else DeviceLinkAvailability.Available,
        newDevice = controller.link?.newDevice,
        moves = controller.link?.moves.orEmpty(),
        busy = controller.running != null || management.devices.running != null,
    )
}

/** What the roster shows about this device's standing and the sign-ins under review. */
private fun linkedDevicesHealth(
    controller: DeviceLinkController,
    management: DeviceManagement,
): LinkedDevicesHealth {
    val busy = management.devices.running != null || management.signIns.running != null
    return LinkedDevicesHealth(
        counts = management.devices.counts,
        keyPackageRefreshOverdue = management.devices.keyPackageRefreshOverdue,
        stalled = controller.link?.deviceGroupStalled == true,
        busy = busy,
        review =
            SignInReviewState(
                list = management.signIns.list,
                warning = management.signIns.warning,
                startedFresh = management.signIns.startedFresh,
                busy = busy,
            ),
    )
}

/** The roster's actions, each sent to the controller that owns it or to the page it opens. */
private fun linkedDevicesActions(
    scope: CoroutineScope,
    controller: DeviceLinkController,
    management: DeviceManagement,
    open: (LinkedDevicesPage) -> Unit,
) = LinkedDevicesActions(
    onRetry = { scope.launch { controller.refreshRoster() } },
    onLinkThisDevice = {
        open(LinkedDevicesPage(linking = true))
        if (!controller.link?.newDevice.inProgress()) scope.launch { controller.start() }
    },
    onDismissMoveSibling = { moveId, slot -> scope.launch { controller.dismissMoveSibling(moveId, slot) } },
    onOpenDevice = { slot -> open(LinkedDevicesPage(deviceSlot = slot)) },
    onRepair = { open(LinkedDevicesPage(repairing = true)) },
    review =
        SignInReviewActions(
            onCheck = { scope.launch { management.signIns.check() } },
            onOpen = { signIn -> open(LinkedDevicesPage(reviewingSlot = signIn.slotIdHex)) },
            onStartFresh = { scope.launch { management.signIns.startFresh() } },
            onDismissWarning = { scope.launch { management.signIns.dismissWarning() } },
        ),
)

/** One device's page, sending each edit to MDK. The page closes by itself once the roster stops listing the device. */
@Composable
private fun LinkedDeviceDetailRoute(
    device: LinkedDeviceRow,
    management: DeviceManagement,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    LinkedDeviceDetailContent(
        device = device,
        busy = management.devices.running != null,
        onBack = onClose,
        actions =
            LinkedDeviceDetailActions(
                onRename = { label -> scope.launch { management.devices.rename(device.slotIdHex, label) } },
                onSettings = { patch -> scope.launch { management.devices.updateSettings(device.slotIdHex, patch) } },
                onRemove = { scope.launch { management.devices.remove(device.slotIdHex) } },
            ),
    )
}

/**
 * This device's own link, shown in place of the roster. Back only closes it: the link carries on in MDK and the
 * roster offers the way back in. Only Cancel cancels, and Try again starts a fresh request.
 */
@Composable
private fun LinkThisDeviceRoute(
    controller: DeviceLinkController,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = controller.link?.newDevice ?: NewDeviceLinkStateFfi.WaitingForAnswer
    BackHandler(onBack = onClose)
    LinkThisDeviceContent(
        step = newDeviceLinkStep(state),
        running = controller.running,
        onBack = onClose,
        onAction = { action ->
            when (action) {
                DeviceLinkAction.ConfirmMatch -> scope.launch { controller.confirmMatch() }
                DeviceLinkAction.Cancel -> scope.launch { controller.cancel() }
                DeviceLinkAction.TryAgain -> scope.launch { controller.start() }
                else -> onClose()
            }
        },
    )
}
