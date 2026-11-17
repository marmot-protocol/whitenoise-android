@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkRequest
import dev.ipf.whitenoise.android.state.DeviceLinkRequestWatcher
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DEVICE_LINK_OTHER_PROFILE_TAG = "device_link.other_profile"
internal const val DEVICE_LINK_REVIEW_TAG = "device_link.review"

private const val DEVICE_LINK_REQUEST_RETRY_MS = 2_000L

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
 * [blocked] holds new presentations back while the app is locked or another protected flow is on screen.
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
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val subscription = runCatchingCancellable { appState.marmotIo { subscribeEvents() } }.getOrNull()
                try {
                    watcher.refresh(currentAccounts.value, currentBlocked.value)
                    while (subscription != null) {
                        val event = withContext(Dispatchers.IO) { subscription.next() } ?: break
                        watcher.onEvent(event, currentAccounts.value, currentBlocked.value)
                    }
                } finally {
                    subscription?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.destroy() } } }
                }
                delay(DEVICE_LINK_REQUEST_RETRY_MS)
            }
        }
    }
    LaunchedEffect(watcher, blocked) { watcher.reoffer(blocked) }
    val request = watcher.queue.presented ?: return
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
        controller.link?.let { watcher.accept(request.accountRef, it, blocked) }
    }
    DeviceLinkFailureEffect(appState, controller)
    Dialog(
        onDismissRequest = { watcher.hidePresented(blocked) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DeviceLinkRequestContent(
            state = prompt.state,
            running = controller.running,
            onLink = { choices -> scope.launch { controller.link(request.promptId, choices) } },
            onDecline = { scope.launch { controller.decline(request.promptId) } },
            onClose = { watcher.hidePresented(blocked) },
        )
    }
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
