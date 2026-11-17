@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.DeviceLinkChoices
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkNative
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DEVICE_LINK_SUBSCRIPTION_RETRY_MS = 2_000L

/** MDK's linked-device reads and link commands through the app's runtime, on its native IO dispatcher. */
internal class RuntimeDeviceLink(
    private val appState: WhiteNoiseAppState,
) : DeviceLinkNative {
    /** Runs one native call on the current runtime's IO dispatcher. */
    private suspend fun <T> io(call: suspend MarmotInterface.() -> T): T = appState.marmotIo(call)

    /** Reads the link snapshot. */
    override suspend fun deviceLinkSnapshot(accountRef: String) = io { deviceLinkSnapshot(accountRef) }

    /** Reads the roster. */
    override suspend fun linkedDevices(accountRef: String) = io { linkedDevices(accountRef) }

    /** Starts this device's link. */
    override suspend fun startDeviceLink(accountRef: String) = io { startDeviceLink(accountRef) }

    /** Confirms the emoji match. */
    override suspend fun confirmDeviceLinkMatch(accountRef: String) = io { confirmDeviceLinkMatch(accountRef) }

    /** Cancels this device's link. */
    override suspend fun cancelDeviceLink(accountRef: String) = io { cancelDeviceLink(accountRef) }

    /** Links the requesting device with the user's choices. */
    override suspend fun linkDevice(
        accountRef: String,
        promptId: String,
        choices: DeviceLinkChoices,
    ) = io { linkDevice(accountRef, promptId, choices.label, choices.addToAllChats, choices.bringHistory) }

    /** Answers the request Not now. */
    override suspend fun declineDeviceLink(
        accountRef: String,
        promptId: String,
    ) = io { declineDeviceLink(accountRef, promptId) }

    /** Ends one old-sibling recognition hint. */
    override suspend fun dismissDeviceGroupMoveSibling(
        accountRef: String,
        moveId: String,
        slotIdHex: String,
    ) = io { dismissDeviceGroupMoveSibling(accountRef, moveId, slotIdHex) }
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
