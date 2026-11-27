@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.hideCompromiseWarning
import dev.ipf.whitenoise.android.state.refreshSignInAlerts
import dev.ipf.whitenoise.android.state.signInAlerts
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBarNavigation
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

internal const val COMPROMISE_ALERT_TAG = "sign_in_alert.compromise_alert"
internal const val COMPROMISE_ALERT_REVIEW_TAG = "sign_in_alert.compromise_alert.review"
internal const val COMPROMISE_ALERT_HIDE_TAG = "sign_in_alert.compromise_alert.hide"
internal const val COMPROMISE_ALERT_NOT_NOW_TAG = "sign_in_alert.compromise_alert.not_now"

private val CompromiseAlertIconSize = 48.dp

/**
 * Opens the full-screen compromise alert over the app once for each warning item of the active account, and keeps
 * that account's alerts current. It waits while [blocked] (the app is locked or another question is open). Every way
 * out counts as shown: Review devices opens the Devices screen, Hide on this device is MDK's dismissal here, and Not
 * now or Close leave the chat list's red card in place.
 */
@Composable
internal fun CompromiseAlertHost(
    appState: WhiteNoiseAppState,
    blocked: Boolean,
) {
    val accountRef = appState.activeAccountRef ?: return
    val alerts = appState.signInAlerts
    // A warning raised before the event stream opened, or for an account just switched to, is read here.
    LaunchedEffect(accountRef, appState.runtimeGeneration) { appState.refreshSignInAlerts(accountRef) }
    val snapshot = alerts.snapshot(accountRef)?.takeIf { !blocked && it.alertDue.isNotEmpty() } ?: return
    val scope = rememberCoroutineScope()
    var hiding by remember(accountRef) { mutableStateOf(false) }
    CompromiseAlert(
        warning = snapshot.warning,
        hiding = hiding,
        onReview = {
            alerts.alertShown(accountRef, snapshot.warning)
            alerts.requestDevices(accountRef)
        },
        onHide = {
            hiding = true
            scope.launch {
                val hidden = appState.hideCompromiseWarning(accountRef)
                alerts.alertShown(accountRef, snapshot.warning)
                if (!hidden) appState.present(R.string.compromise_warning_hide_failed)
                hiding = false
            }
        },
        onNotNow = { alerts.alertShown(accountRef, snapshot.warning) },
    )
}

/** The full-screen alert as its own window over whatever screen is open. */
@Composable
internal fun CompromiseAlert(
    warning: List<CompromiseWarningItemFfi>,
    hiding: Boolean,
    onReview: () -> Unit,
    onHide: () -> Unit,
    onNotNow: () -> Unit,
) {
    Dialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompromiseAlertContent(warning, hiding, onReview, onHide, onNotNow)
    }
}

/**
 * The alert's page: the reasons MDK gave, what the key can still do, the three steps, then Review devices, Hide on
 * this device and Not now. Close in the top bar is the same as Not now.
 */
@Composable
internal fun CompromiseAlertContent(
    warning: List<CompromiseWarningItemFfi>,
    hiding: Boolean,
    onReview: () -> Unit,
    onHide: () -> Unit,
    onNotNow: () -> Unit,
) {
    WhiteNoiseScaffold(
        modifier = Modifier.testTag(COMPROMISE_ALERT_TAG),
        topBar = {
            WhiteNoiseTopBar(
                title = stringResource(R.string.sign_in_alert_badge_compromise),
                onBack = onNotNow,
                navigation = WhiteNoiseTopBarNavigation.Close,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .whiteNoiseVerticalScroll()
                    .padding(WhiteNoiseSpacing.CompactScreenMargin),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier.size(CompromiseAlertIconSize),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.compromise_warning_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(compromiseWarningReasons(warning), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.compromise_warning_explainer), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.compromise_warning_steps_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(compromiseWarningSteps(), style = MaterialTheme.typography.bodyLarge)
            CompromiseAlertActions(hiding, onReview, onHide, onNotNow)
        }
    }
}

/** The alert's three actions, full width and stacked so each stays readable at any font size. */
@Composable
private fun CompromiseAlertActions(
    hiding: Boolean,
    onReview: () -> Unit,
    onHide: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        WhiteNoiseButton(onClick = onReview, modifier = Modifier.fillMaxWidth().testTag(COMPROMISE_ALERT_REVIEW_TAG)) {
            Text(stringResource(R.string.compromise_alert_review))
        }
        WhiteNoiseOutlinedButton(
            onClick = onHide,
            enabled = !hiding,
            modifier = Modifier.fillMaxWidth().testTag(COMPROMISE_ALERT_HIDE_TAG),
        ) { Text(stringResource(R.string.compromise_warning_dismiss)) }
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().testTag(COMPROMISE_ALERT_NOT_NOW_TAG)) {
            Text(stringResource(R.string.link_request_not_now))
        }
    }
}
