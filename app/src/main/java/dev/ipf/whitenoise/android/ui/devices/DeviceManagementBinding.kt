@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SignInResponseFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkOffers
import dev.ipf.whitenoise.android.state.DeviceRepairConfirmationRequired
import dev.ipf.whitenoise.android.state.DeviceRepairPublicationFailed
import dev.ipf.whitenoise.android.state.LinkedDeviceActionFailure
import dev.ipf.whitenoise.android.state.LinkedDeviceManagementController
import dev.ipf.whitenoise.android.state.LinkedDeviceManagementNative
import dev.ipf.whitenoise.android.state.LinkedDeviceSettingsPatch
import dev.ipf.whitenoise.android.state.SignInReviewController
import dev.ipf.whitenoise.android.state.SignInReviewNative
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MDK's device-management calls through the app's runtime, on its native IO dispatcher. */
internal class RuntimeLinkedDeviceManagement(
    private val appState: WhiteNoiseAppState,
) : LinkedDeviceManagementNative {
    /** Runs one native call on the current runtime's IO dispatcher. */
    private suspend fun <T> io(call: suspend MarmotInterface.() -> T): T = appState.marmotIo(call)

    /** Renames a device. */
    override suspend fun renameLinkedDevice(
        accountRef: String,
        slotIdHex: String,
        label: String,
    ) = io { renameLinkedDevice(accountRef, slotIdHex, label) }

    /** Patches a device's settings, passing null for the fields MDK should keep. */
    override suspend fun updateLinkedDeviceSettings(
        accountRef: String,
        slotIdHex: String,
        patch: LinkedDeviceSettingsPatch,
    ) = io {
        updateLinkedDeviceSettings(
            accountRef,
            slotIdHex,
            patch.addToAllChats,
            patch.bringHistory,
            patch.keepWhileInactive,
        )
    }

    /** Removes another device. */
    override suspend fun removeLinkedDevice(
        accountRef: String,
        slotIdHex: String,
    ) = io { removeLinkedDevice(accountRef, slotIdHex) }

    /** Reads the count of chats still holding a removed device. */
    override suspend fun removedDeviceLeftoverChats(accountRef: String) = io { removedDeviceLeftoverChats(accountRef) }

    /** Reads the count of chats closed to linked devices. */
    override suspend fun chatsClosedToLinkedDevices(accountRef: String) = io { chatsClosedToLinkedDevices(accountRef) }

    /** Reads whether the KeyPackage refresh is overdue. */
    override suspend fun keyPackageRefreshOverdue(accountRef: String) = io { keyPackageRefreshOverdue(accountRef) }

    /** Reads the installation's durable receipt for this profile, including after process death. */
    override suspend fun repairNeedsConfirmation(accountRef: String): Boolean {
        val owner = appState.accounts.firstOrNull { it.label == accountRef }?.accountIdHex ?: return false
        return withContext(Dispatchers.IO) {
            DeviceLinkOffers.shared(appState.appContext).repair(owner).needsConfirmation
        }
    }

    /** Discards the old copy and publishes a fresh KeyPackage before a new link can start. */
    override suspend fun resetDeviceGroupForRepair(accountRef: String) =
        appState.withAccountLifetime {
            val account = checkNotNull(appState.activeAccount?.takeIf { it.label == accountRef })
            io {
                DeviceLinkOffers.shared(appState.appContext).repair(account.accountIdHex).reset(
                    discard = { resetDeviceGroupForRepair(accountRef) },
                    rotate = { rotateKeyPackage(accountRef) },
                )
            }
        }
}

/** MDK's sign-in review calls through the app's runtime, on its native IO dispatcher. */
internal class RuntimeSignInReview(
    private val appState: WhiteNoiseAppState,
) : SignInReviewNative {
    /** Runs one native call on the current runtime's IO dispatcher. */
    private suspend fun <T> io(call: suspend MarmotInterface.() -> T): T = appState.marmotIo(call)

    /** Reads the unrecognized sign-ins from local state. */
    override suspend fun unrecognizedSignIns(accountRef: String) = io { unrecognizedSignIns(accountRef) }

    /** Checks the relays for sign-ins now. */
    override suspend fun checkSignIns(accountRef: String) = io { checkSignIns(accountRef) }

    /** Answers one sign-in. */
    override suspend fun answerSignIn(
        accountRef: String,
        slotIdHex: String,
        response: SignInResponseFfi,
    ) = io { answerSignIn(accountRef, slotIdHex, response) }

    /** Reads the open compromise warning. */
    override suspend fun compromiseWarning(accountRef: String) = io { compromiseWarning(accountRef) }

    /** Marks the warning seen on this device. */
    override suspend fun dismissCompromiseWarning(accountRef: String) = io { dismissCompromiseWarning(accountRef) }

    /** Reads whether this device started fresh. */
    override suspend fun startedFresh(accountRef: String) = io { startedFresh(accountRef) }

    /** Starts this device fresh. */
    override suspend fun startFresh(accountRef: String) = io { startFresh(accountRef) }
}

/** The two controllers behind device management and sign-in review for one account on one runtime. */
internal class DeviceManagement(
    val devices: LinkedDeviceManagementController,
    val signIns: SignInReviewController,
) {
    /** Stops both from publishing. */
    fun retire() {
        devices.retire()
        signIns.retire()
    }

    /** Re-reads everything both mirror. */
    suspend fun refresh() {
        devices.refresh()
        signIns.refresh()
    }

    /** Passes one MDK event to both. */
    suspend fun onEvent(event: MarmotEventFfi) {
        devices.onEvent(event)
        signIns.onEvent(event)
    }
}

/**
 * Binds the active account's device management and sign-in review to this composition. New controllers are made
 * for every account or runtime change and the old ones retired. While the screen is started they read once and
 * then follow MDK's change events; returning to the foreground reads again.
 */
@Composable
internal fun rememberDeviceManagement(appState: WhiteNoiseAppState): DeviceManagement? {
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val management =
        remember(accountRef, runtimeGeneration) {
            accountRef?.let { account ->
                val owns = { appState.activeAccountRef == account && appState.runtimeGeneration == runtimeGeneration }
                DeviceManagement(
                    LinkedDeviceManagementController(account, RuntimeLinkedDeviceManagement(appState), owns),
                    SignInReviewController(account, RuntimeSignInReview(appState), owns),
                )
            }
        }
    DisposableEffect(management) { onDispose { management?.retire() } }
    LaunchedEffect(management, lifecycle) {
        val owner = management ?: return@LaunchedEffect
        appState.followEventsWhileStarted(lifecycle) { nextEvent ->
            owner.refresh()
            while (nextEvent != null) owner.onEvent(nextEvent() ?: break)
        }
    }
    return management
}

/**
 * Shows a failed management or review action once. A busy device group and a link still pending get their own
 * advice, because waiting fixes them; anything else is the app's copyable failure notice.
 */
@Composable
internal fun DeviceManagementFailureEffect(
    appState: WhiteNoiseAppState,
    management: DeviceManagement,
) {
    val deviceFailure = management.devices.failure
    LaunchedEffect(deviceFailure) {
        when (deviceFailure) {
            null -> return@LaunchedEffect
            is LinkedDeviceActionFailure.Busy -> appState.present(R.string.device_action_busy)
            is LinkedDeviceActionFailure.LinkPending -> appState.present(R.string.device_action_link_pending)
            is LinkedDeviceActionFailure.Other -> {
                val title =
                    when (deviceFailure.cause) {
                        is DeviceRepairPublicationFailed -> R.string.device_repair_try_link_again
                        is DeviceRepairConfirmationRequired -> R.string.device_repair_confirm_again
                        else -> R.string.device_action_failed
                    }
                appState.presentFailure(title, "LINKED_DEVICE_ACTION", deviceFailure.cause)
            }
        }
        management.devices.failureShown()
    }
    val reviewFailure = management.signIns.failure
    LaunchedEffect(reviewFailure) {
        if (reviewFailure != null) {
            appState.presentFailure(R.string.sign_in_answer_failed, "SIGN_IN_REVIEW", reviewFailure.cause)
            management.signIns.failureShown()
        }
    }
}
