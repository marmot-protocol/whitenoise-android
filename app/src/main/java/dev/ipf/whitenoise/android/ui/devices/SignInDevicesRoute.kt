@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.SignInAlertTier
import dev.ipf.whitenoise.android.state.SignInDevicesRequest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.signInAlerts
import dev.ipf.whitenoise.android.state.signInAlertsApply
import dev.ipf.whitenoise.android.ui.common.chatRowBadgeDiameter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val DEVICES_ROW_BADGE_TAG = "settings.account.linked_devices.badge"

/**
 * Carries a request to open an account's Devices screen (from the chat-list notice, the full-screen alert or a
 * notification tap) to Settings → Account. A request for another account switches to it first, and the app moves
 * only once that account is active. [onOpenAccount] opens Settings → Account; the Account screen opens Devices.
 */
@Composable
internal fun SignInDevicesRouteEffect(
    appState: WhiteNoiseAppState,
    navigationAccount: String?,
    onOpenAccount: () -> Unit,
) {
    val request = appState.signInAlerts.devicesRequest
    val active = appState.activeAccountRef
    val unlocked = appState.deviceRoutingUnlocked

    LaunchedEffect(request, active, navigationAccount, appState.phase, unlocked) {
        if (request == null || request.accountOpened || appState.phase != AppPhase.Ready) return@LaunchedEffect
        if (!appState.canRouteDevices(request)) return@LaunchedEffect
        when {
            !appState.devicesAccountEligible(request) -> appState.signInAlerts.updateDevicesRequest(request)
            appState.activeAccountRef == request.accountRef -> {
                if (navigationAccount == request.accountRef) {
                    onOpenAccount()
                    appState.signInAlerts.updateDevicesRequest(request, request.copy(accountOpened = true))
                }
            }
            else -> appState.activateDevicesAccount(request)
        }
    }
}

/** Opens Devices with [onOpen] when a request names the active account, then ends the request. */
@Composable
internal fun SignInDevicesRequestEffect(
    appState: WhiteNoiseAppState,
    onOpen: () -> Unit,
) {
    val request = appState.signInAlerts.devicesRequest
    LaunchedEffect(request, appState.activeAccountRef, appState.deviceRoutingUnlocked) {
        if (request?.accountOpened != true || request.accountRef != appState.activeAccountRef) return@LaunchedEffect
        if (!appState.canRouteDevices(request) || !appState.devicesAccountEligible(request)) return@LaunchedEffect
        onOpen()
        appState.signInAlerts.updateDevicesRequest(request)
    }
}

private val WhiteNoiseAppState.deviceRoutingUnlocked: Boolean
    get() = !appLock.screenVisible && !appLock.evaluationPending

private fun WhiteNoiseAppState.canRouteDevices(request: SignInDevicesRequest): Boolean {
    val current = signInAlerts.devicesRequest
    return deviceRoutingUnlocked && current == request
}

private fun WhiteNoiseAppState.devicesAccountEligible(request: SignInDevicesRequest): Boolean =
    accounts.any { it.label == request.accountRef && signInAlertsApply(it) }

private fun WhiteNoiseAppState.activateDevicesAccount(request: SignInDevicesRequest) {
    launchMutation {
        val activated =
            setActiveAccount(
                request.accountRef,
                shouldActivate = { canRouteDevices(request) && devicesAccountEligible(request) },
            )
        if (!activated && deviceRoutingUnlocked) signInAlerts.updateDevicesRequest(request)
    }
}

/** The active account's alert tier for the Devices row badge. */
internal fun WhiteNoiseAppState.devicesRowTier(): SignInAlertTier {
    val snapshot = signInAlerts.snapshot(activeAccountRef)
    return snapshot?.tier ?: SignInAlertTier.Passive
}

/**
 * The Devices row's end: a dot before the chevron while a sign-in waits for review (primary) or the compromise
 * warning is open (error), with its meaning for TalkBack; just the chevron otherwise.
 */
@Composable
internal fun DevicesRowTrailing(tier: SignInAlertTier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        if (tier != SignInAlertTier.Passive) {
            val compromise = tier == SignInAlertTier.Compromise
            val description =
                stringResource(
                    if (compromise) R.string.sign_in_alert_badge_compromise else R.string.sign_in_alert_badge_review,
                )
            val diameter = chatRowBadgeDiameter()
            Badge(
                modifier =
                    Modifier
                        .sizeIn(minWidth = diameter, minHeight = diameter)
                        .testTag(DEVICES_ROW_BADGE_TAG)
                        .semantics { contentDescription = description },
                containerColor = if (compromise) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor =
                    if (compromise) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
    }
}
