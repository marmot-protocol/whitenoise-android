@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkCommandFailure
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkRequest
import dev.ipf.whitenoise.android.state.DeviceLinkRequestWatcher
import dev.ipf.whitenoise.android.state.DeviceRepairConfirmationRequired
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.decline
import dev.ipf.whitenoise.android.state.link
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val DEVICE_LINK_OTHER_PROFILE_TAG = "device_link.other_profile"
internal const val DEVICE_LINK_REVIEW_TAG = "device_link.review"
internal const val DEVICE_LINK_REVIEW_PENDING_TAG = "device_link.review.pending"

/** How long a reviewed profile waits between reads while its request is still unchecked. */
internal const val DEVICE_LINK_REVIEW_RETRY_MS = 2_000L

/**
 * The profiles whose link requests the shell watches: signed in, and signing with a key held on this device.
 * Linking is not offered yet for accounts that sign through an external signer app.
 */
internal fun deviceLinkWatchedAccounts(accounts: List<AccountSummaryFfi>): List<String> {
    val eligible = accounts.filter { !it.signedOut && it.localSigning }
    return eligible.map { it.label }
}

/**
 * Watches link requests from every signed-in profile and presents the one the user opened from the chat list's
 * notice ([DeviceLinkRequestNotice]); no request is presented on its own. Requests are read while the app is in the
 * foreground: each return to the foreground re-reads MDK, so a request that expired in the background shows as ended
 * rather than reappearing, and nothing runs or notifies while the app is away. [blocked] keeps the notice from
 * opening anything, and takes down a request already showing, while the app is locked or another protected flow is on
 * screen.
 *
 * An opened request on the active profile shows its comparison. One on another profile is only announced: Review
 * activates that profile and re-reads its prompt before the comparison is shown, and nothing is answered for a
 * profile that is not active. A repeated request's warning can open Devices over the app.
 */
@Composable
internal fun DeviceLinkRequestHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    val runtimeGeneration = appState.runtimeGeneration
    val accounts = deviceLinkWatchedAccounts(appState.accounts)
    val currentAccounts = rememberUpdatedState(accounts)
    val watcher = appState.deviceLinkRequests
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(watcher, lifecycle, accounts) {
        appState.followEventsWhileStarted(lifecycle) { nextEvent ->
            watcher.refresh(currentAccounts.value)
            while (nextEvent != null) {
                watcher.onEvent(nextEvent() ?: break, currentAccounts.value)
            }
        }
    }
    SideEffect { watcher.blocked = blocked }
    LaunchedEffect(watcher, appState.activeAccountRef) { watcher.reoffer() }
    var devicesOpen by remember(appState.activeAccountRef, runtimeGeneration) { mutableStateOf(false) }
    val reporting = remember(watcher) { mutableStateOf(false) }
    if (devicesOpen && !blocked) DeviceLinkDevicesWindow(appState, onClose = { devicesOpen = false })
    // A request already on screen is taken down while the app is locked, so nothing can be answered until unlock.
    val request = watcher.queue.presented ?: return
    val showDevices = {
        watcher.close()
        devicesOpen = true
    }
    DeviceLinkRequestPresentation(
        watcher = watcher,
        request = request,
        activeAccountRef = appState.activeAccountRef,
        blocked = blocked,
        profileName = appState.profileName(request.accountRef),
        onReview = {
            appState.launchMutation {
                if (appState.setActiveAccount(request.accountRef)) {
                    watcher.refresh(currentAccounts.value)
                }
            }
        },
    ) {
        DeviceLinkApprovalGate(runtimeGeneration, request, blocked) { draft ->
            ActiveProfileLinkRequest(
                appState = appState,
                watcher = watcher,
                request = request,
                draft = draft,
                reporting = { reporting.value },
                onReport = {
                    launchLinkReport(appState, watcher, request, runtimeGeneration, reporting, showDevices)
                },
                onReviewDevices = showDevices,
            )
        }
    }
}

/** A Review that switched to [accountRef] after [readsBefore] successful reads of it, until the next one lands. */
internal data class DeviceLinkReview(
    val accountRef: String,
    val readsBefore: Long,
) {
    /** Whether [accountRef]'s comparison must still wait, because no read has succeeded since the switch. */
    fun awaits(reads: Map<String, Long>): Boolean = (reads[accountRef] ?: 0L) <= readsBefore
}

/**
 * Shows the presented [request]: on the active profile its comparison ([activeRequest]), on another profile only
 * the notice, whose Review runs [onReview] to activate that profile and re-read its prompt. Until a read made after
 * Review has succeeded, the request shows as being checked, with Not now and nothing to answer, and while the app is
 * started its profile is read again every [DEVICE_LINK_REVIEW_RETRY_MS]: a failed read raises no event and the
 * watcher keeps no timer, so neither a slow read nor a failed one can show the cached prompt or strand the request.
 */
@Composable
internal fun DeviceLinkRequestPresentation(
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    activeAccountRef: String?,
    blocked: Boolean,
    profileName: String,
    onReview: () -> Unit,
    activeRequest: @Composable () -> Unit,
) {
    var review by remember(watcher) { mutableStateOf<DeviceLinkReview?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (request.accountRef == activeAccountRef) {
        val pending = review?.takeIf { it.accountRef == request.accountRef }?.awaits(watcher.successfulReads) == true
        if (!pending) {
            activeRequest()
        } else {
            LaunchedEffect(review, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(DEVICE_LINK_REVIEW_RETRY_MS)
                        watcher.reread(request.accountRef)
                    }
                }
            }
            if (!blocked) DeviceLinkReviewPendingDialog(onNotNow = watcher::close)
        }
    } else if (!blocked && request.open) {
        DeviceLinkOtherProfileDialog(
            profileName = profileName,
            onReview = {
                review = DeviceLinkReview(request.accountRef, watcher.successfulReads[request.accountRef] ?: 0L)
                onReview()
            },
            onNotNow = watcher::close,
        )
    }
}

/** A lock hides the comparison. Only a different runtime, profile or prompt resets the entered choices. */
@Composable
internal fun DeviceLinkApprovalGate(
    runtimeGeneration: Int,
    request: DeviceLinkRequest,
    blocked: Boolean,
    content: @Composable (DeviceLinkApprovalDraft) -> Unit,
) {
    key(runtimeGeneration, request.accountRef, request.promptId) {
        val draft = rememberApprovalDraft()
        if (!blocked) content(draft)
    }
}

/** The name the app shows for the profile [accountRef], from what is already cached. */
internal fun WhiteNoiseAppState.profileName(accountRef: String): String {
    val accountId = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
    return accountId?.let(::accountDisplayNameCached) ?: accountRef
}

/**
 * The active profile's request over the whole window. Link device and Not now go to MDK through a controller bound
 * to this profile and runtime; closing the window only returns the request to the chat list's notice. A failed
 * command is shown once and leaves the request, and the choices entered, in place. Touches that began before the
 * window appeared are ignored, and a repeated request's Review devices runs [onReviewDevices].
 */
@Suppress("LongParameterList")
@Composable
private fun ActiveProfileLinkRequest(
    appState: WhiteNoiseAppState,
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    draft: DeviceLinkApprovalDraft,
    reporting: () -> Boolean,
    onReport: () -> Unit,
    onReviewDevices: () -> Unit,
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
    var beforeCommand by remember(controller) { mutableStateOf<DeviceLinkSnapshotFfi?>(null) }
    LaunchedEffect(controller.link) {
        val result = controller.link ?: return@LaunchedEffect
        watcher.accept(request.accountRef, result, beforeCommand = beforeCommand)
        // This effect runs a frame after the command, so MDK's own event may already have published a newer state.
        watcher.reread(request.accountRef)
    }
    val failureMessage = rememberLinkFailureMessage(controller)

    fun canRespond(): Boolean =
        !reporting() &&
            !watcher.blocked &&
            watcher.queue.presented?.id == request.id &&
            appState.activeAccountRef == request.accountRef &&
            appState.runtimeGeneration == runtimeGeneration
    val reportCurrent: () -> Unit = {
        if (canRespond() && controller.running == null) onReport()
    }
    DeviceLinkConsentDialog(appState, watcher, request, prompt, ::canRespond, reportCurrent) { canApprove ->
        DeviceLinkRequestContent(
            state = prompt.state,
            running = controller.running,
            onLink = { choices ->
                scope.launch {
                    if (!canApprove()) return@launch
                    beforeCommand = watcher.snapshots[request.accountRef]
                    controller.link(request.promptId, choices)
                }
            },
            onDecline = {
                beforeCommand = watcher.snapshots[request.accountRef]
                scope.launch {
                    if (canRespond()) controller.decline(request.promptId)
                }
            },
            onClose = watcher::close,
            failureMessage = failureMessage,
            draft = draft,
            repeated = request.repeated,
            onReviewDevices = onReviewDevices,
            onReport = reportCurrent,
        )
    }
}

/**
 * Retains the reason for a refusal on the full-window link step until the next command starts.
 */
@Composable
internal fun rememberLinkFailureMessage(controller: DeviceLinkController): Int? {
    var message by remember(controller) { mutableStateOf<Int?>(null) }
    val failure = controller.failure
    val running = controller.running
    LaunchedEffect(failure, running) {
        if (failure != null) {
            message = failure.messageResource()
            controller.failureShown()
        } else if (running != null) {
            message = null
        }
    }
    return message
}

/** A lost repair outcome needs an explicit decision in Settings before a new link can start. */
private fun DeviceLinkCommandFailure.messageResource(): Int =
    if (cause is DeviceRepairConfirmationRequired) {
        R.string.device_repair_confirm_again
    } else {
        R.string.link_device_action_failed
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
            appState.presentFailure(failure.messageResource(), "DEVICE_LINK", failure.cause)
            controller.failureShown()
        }
    }
}

/** A reviewed request whose profile has not been read again yet: it says so and can only be put off. */
@Composable
internal fun DeviceLinkReviewPendingDialog(onNotNow: () -> Unit) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag(DEVICE_LINK_REVIEW_PENDING_TAG),
        title = { Text(stringResource(R.string.link_request_title)) },
        text = { Text(stringResource(R.string.link_request_preparing)) },
        confirmButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.link_request_not_now)) } },
    )
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
