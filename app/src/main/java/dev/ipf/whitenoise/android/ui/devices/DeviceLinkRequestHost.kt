@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkRequest
import dev.ipf.whitenoise.android.state.DeviceLinkRequestWatcher
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.decline
import dev.ipf.whitenoise.android.state.link
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import kotlinx.coroutines.launch

internal const val DEVICE_LINK_OTHER_PROFILE_TAG = "device_link.other_profile"
internal const val DEVICE_LINK_REVIEW_TAG = "device_link.review"

/**
 * The profiles whose link requests the shell watches: signed in, and signing with a key held on this device.
 * Linking is not offered yet for accounts that sign through an external signer app.
 */
internal fun deviceLinkWatchedAccounts(accounts: List<AccountSummaryFfi>): List<String> {
    val eligible = accounts.filter { !it.signedOut && it.localSigning }
    return eligible.map { it.label }
}

/**
 * Presents link requests from any signed-in profile above the app shell. Requests are read while the app is in the
 * foreground: each return to the foreground re-reads MDK before anything is presented, so a request that expired in
 * the background shows as ended rather than reappearing, and nothing runs or notifies while the app is away.
 * [blocked] holds new presentations back, and takes down one already showing, while the app is locked or another
 * protected flow is on screen.
 *
 * A request on the active profile opens its comparison. A request on another profile is only announced: Review
 * activates that profile and re-reads its prompt before the comparison is shown, and nothing is answered for a
 * profile that is not active.
 */
@Composable
internal fun DeviceLinkRequestHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    val runtimeGeneration = appState.runtimeGeneration
    val accounts = deviceLinkWatchedAccounts(appState.accounts)
    val currentAccounts = rememberUpdatedState(accounts)
    val currentBlocked = rememberUpdatedState(blocked)
    val watcher =
        remember(runtimeGeneration) {
            DeviceLinkRequestWatcher(
                readSnapshot = { account -> appState.marmotIo { deviceLinkSnapshot(account) } },
                owns = { appState.runtimeGeneration == runtimeGeneration },
            )
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(watcher, lifecycle, accounts) {
        appState.followEventsWhileStarted(lifecycle) { nextEvent ->
            watcher.refresh(currentAccounts.value, currentBlocked.value)
            while (nextEvent != null) {
                watcher.onEvent(nextEvent() ?: break, currentAccounts.value, currentBlocked.value)
            }
        }
    }
    LaunchedEffect(watcher, blocked) { watcher.reoffer(blocked) }
    // A request already on screen is taken down while the app is locked, so nothing can be answered until unlock.
    val request = watcher.queue.presented?.takeUnless { blocked } ?: return
    if (request.accountRef == appState.activeAccountRef) {
        ActiveProfileLinkRequest(appState, watcher, request, blocked)
    } else {
        DeviceLinkOtherProfileDialog(
            profileName = appState.profileName(request.accountRef),
            onReview = {
                appState.launchMutation {
                    if (appState.setActiveAccount(request.accountRef)) {
                        watcher.refresh(currentAccounts.value, currentBlocked.value)
                    }
                }
            },
            onNotNow = { watcher.hidePresented(blocked) },
        )
    }
}

/** The name the app shows for the profile [accountRef], from what is already cached. */
private fun WhiteNoiseAppState.profileName(accountRef: String): String {
    val accountId = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
    return accountId?.let(::accountDisplayNameCached) ?: accountRef
}

/**
 * The active profile's request over the whole window. Link device and Not now go to MDK through a controller bound
 * to this profile and runtime; closing the window only hides the request. A failed command is shown once and leaves
 * the request, and the choices entered, in place.
 */
@Composable
private fun ActiveProfileLinkRequest(
    appState: WhiteNoiseAppState,
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    blocked: Boolean,
) {
    val prompt = watcher.presentedPrompt ?: return
    val runtimeGeneration = appState.runtimeGeneration
    val scope = rememberCoroutineScope()
    val controller =
        remember(request.accountRef, runtimeGeneration) {
            DeviceLinkController(
                accountRef = request.accountRef,
                native = RuntimeDeviceLink(appState),
                owns = {
                    appState.activeAccountRef == request.accountRef && appState.runtimeGeneration == runtimeGeneration
                },
            )
        }
    DisposableEffect(controller) { onDispose { controller.retire() } }
    LaunchedEffect(controller.link) {
        val result = controller.link ?: return@LaunchedEffect
        watcher.accept(request.accountRef, result, blocked)
        // This effect runs a frame after the command, so MDK's own event may already have published a newer state.
        watcher.reread(request.accountRef, blocked)
    }
    val failed = rememberDeviceLinkFailed(controller)
    Dialog(
        onDismissRequest = { watcher.hidePresented(blocked) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DeviceLinkRequestContent(
            state = prompt.state,
            running = controller.running,
            onLink = { choices -> scope.launch { controller.link(request.promptId, choices) } },
            onDecline = { scope.launch { controller.decline(request.promptId) } },
            onClose = { watcher.hidePresented(blocked) },
            failed = failed,
        )
    }
}

/**
 * Whether [controller]'s last command failed, for a step drawn over the whole window where the app's failure notice
 * would sit unseen behind it. The flag clears when the next command starts.
 */
@Composable
internal fun rememberDeviceLinkFailed(controller: DeviceLinkController): Boolean {
    var failed by remember(controller) { mutableStateOf(false) }
    val failure = controller.failure
    val running = controller.running
    LaunchedEffect(failure, running) {
        if (failure != null) {
            failed = true
            controller.failureShown()
        } else if (running != null) {
            failed = false
        }
    }
    return failed
}

/** Shows a failed link command once as the app's copyable failure notice. */
@Composable
internal fun DeviceLinkFailureEffect(
    appState: WhiteNoiseAppState,
    controller: DeviceLinkController,
) {
    val failure = controller.failure
    LaunchedEffect(failure) {
        if (failure != null) {
            appState.presentFailure(R.string.link_device_action_failed, "DEVICE_LINK", failure.cause)
            controller.failureShown()
        }
    }
}

/**
 * Tells the user a device is asking to link to a profile that is not the active one. Review switches to that
 * profile; Not now hides the notice and answers nothing.
 */
@Composable
internal fun DeviceLinkOtherProfileDialog(
    profileName: String,
    onReview: () -> Unit,
    onNotNow: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag(DEVICE_LINK_OTHER_PROFILE_TAG),
        title = { Text(stringResource(R.string.link_request_title)) },
        text = { Text(stringResource(R.string.link_request_other_profile, profileName)) },
        confirmButton = {
            TextButton(onClick = onReview, modifier = Modifier.testTag(DEVICE_LINK_REVIEW_TAG)) {
                Text(stringResource(R.string.link_request_review))
            }
        },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.link_request_not_now)) } },
    )
}
