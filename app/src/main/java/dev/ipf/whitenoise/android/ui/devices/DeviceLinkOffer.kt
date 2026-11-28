@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.DeviceLinkController
import dev.ipf.whitenoise.android.state.DeviceLinkOfferStage
import dev.ipf.whitenoise.android.state.DeviceLinkOffers
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.start
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog

internal const val DEVICE_LINK_OFFER_TAG = "device_link.offer"
internal const val DEVICE_LINK_OFFER_LINK_TAG = "device_link.offer.link"
internal const val DEVICE_LINK_REMINDER_TAG = "device_link.reminder"
internal const val DEVICE_LINK_REMINDER_LINK_TAG = "device_link.reminder.link"
internal const val DEVICE_LINK_REMINDER_DISMISS_TAG = "device_link.reminder.dismiss"

/**
 * The account this device may offer to link: the active one, signed in, signing with a key held here. Linking is
 * not offered yet for accounts that sign through an external signer app.
 */
private fun WhiteNoiseAppState.linkOfferAccount(): String? {
    val account = activeAccount?.takeIf { it.localSigning && !it.signedOut }
    return account?.accountIdHex
}

/**
 * Offers to link this device once after a sign-in that found the account on another installation, and shows the
 * linking steps over the app when the offer or the chat list's reminder asks for them. [blocked] holds both back
 * while the app is locked or another protected flow is on screen.
 */
@Composable
internal fun DeviceLinkOfferHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    val offers = remember { DeviceLinkOffers.shared(appState.appContext) }
    val accountIds = appState.accounts.map { it.accountIdHex }
    // A failed account read publishes an empty list that is not authoritative, so only a real read prunes offers.
    val accountsRead = appState.accountListLoaded
    LaunchedEffect(accountIds, accountsRead) { if (accountsRead) offers.retainAccounts(accountIds) }
    val accountIdHex = appState.linkOfferAccount()?.takeUnless { blocked } ?: return
    if (offers.linkingFor == accountIdHex) {
        DeviceLinkOfferSteps(appState, offers)
    } else if (offers.stage(accountIdHex) == DeviceLinkOfferStage.Offer) {
        DeviceLinkOfferDialog(
            onLink = { offers.openLinking(accountIdHex) },
            onNotNow = { offers.postpone(accountIdHex) },
        )
    }
}

/**
 * This device's linking steps over the whole window. The tap that opens them re-reads MDK and starts a request
 * only when the read succeeded and none is under way. Steps redrawn later, after a lock or a rotation, start
 * nothing: an ended link stays ended until the user chooses Try again. Closing only hides the steps.
 */
@Composable
private fun DeviceLinkOfferSteps(
    appState: WhiteNoiseAppState,
    offers: DeviceLinkOffers,
) {
    val controller = rememberDeviceLinkController(appState) ?: return
    val failureMessage = rememberLinkFailureMessage(controller)
    DeviceLinkOfferSettled(appState, controller)
    LaunchedEffect(controller) {
        controller.refreshLink()
        val state = controller.link?.newDevice
        val startable = !controller.linkReadFailed && !state.inProgress() && state !is NewDeviceLinkStateFfi.Linked
        // Only the tap that opened the steps starts a request. Redrawn steps show MDK's state, ended or not.
        if (offers.takeStartRequest() && startable) controller.start()
    }
    Dialog(
        onDismissRequest = offers::closeLinking,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LinkThisDeviceRoute(controller, onClose = offers::closeLinking, failureMessage = failureMessage)
    }
}

/**
 * Ends the active account's offer and reminder once MDK says this device is linked or lists another device. When
 * the link completes while this is on screen, it also starts the chat list's hint that chats are still arriving.
 */
@Composable
internal fun DeviceLinkOfferSettled(
    appState: WhiteNoiseAppState,
    controller: DeviceLinkController,
) {
    val state = controller.link?.newDevice
    val linked = state is NewDeviceLinkStateFfi.Linked
    val settled = linked || controller.roster.roster?.hasOtherDevices == true
    val accountIdHex = appState.activeAccount?.accountIdHex
    LaunchedEffect(settled, accountIdHex) {
        val offers = DeviceLinkOffers.shared(appState.appContext)
        if (settled && accountIdHex != null) offers.clear(accountIdHex)
    }
}

/**
 * Asks, once after sign-in, whether to link this device to the account's other devices. Closing the dialog is the
 * same as Not now: nothing is requested and the chat list keeps a reminder.
 */
@Composable
internal fun DeviceLinkOfferDialog(
    onLink: () -> Unit,
    onNotNow: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag(DEVICE_LINK_OFFER_TAG),
        title = { Text(stringResource(R.string.link_offer_title)) },
        text = { Text(stringResource(R.string.link_offer_detail)) },
        confirmButton = {
            TextButton(onClick = onLink, modifier = Modifier.testTag(DEVICE_LINK_OFFER_LINK_TAG)) {
                Text(stringResource(R.string.link_device_this_title))
            }
        },
        dismissButton = { TextButton(onClick = onNotNow) { Text(stringResource(R.string.link_request_not_now)) } },
    )
}

/**
 * The chat list's reminder for the active account after Not now. Link this device opens the linking steps over the
 * app; Dismiss ends the reminder, and linking stays available from Settings.
 */
@Composable
internal fun DeviceLinkReminder(appState: WhiteNoiseAppState) {
    val accountIdHex = appState.linkOfferAccount()?.takeIf { linkedDevicesAvailable() } ?: return
    val offers = remember { DeviceLinkOffers.shared(appState.appContext) }
    if (offers.stage(accountIdHex) == DeviceLinkOfferStage.Reminder) {
        DeviceLinkReminderBanner(
            onLink = { offers.openLinking(accountIdHex) },
            onDismiss = { offers.clear(accountIdHex) },
        )
    }
}

/** The reminder's text above its two actions, which wrap below the text at any width or font size. */
@Composable
internal fun DeviceLinkReminderBanner(
    onLink: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DeviceLinkNoticeBanner(
        text = stringResource(R.string.link_offer_reminder),
        modifier = modifier.testTag(DEVICE_LINK_REMINDER_TAG),
    ) {
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(DEVICE_LINK_REMINDER_DISMISS_TAG)) {
            Text(stringResource(R.string.dismiss))
        }
        TextButton(onClick = onLink, modifier = Modifier.testTag(DEVICE_LINK_REMINDER_LINK_TAG)) {
            Text(stringResource(R.string.link_device_this_title))
        }
    }
}
