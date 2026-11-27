@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.SignInAlertTier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.hideCompromiseWarning
import dev.ipf.whitenoise.android.state.signInAlerts
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

internal const val SIGN_IN_REVIEW_BANNER_TAG = "sign_in_alert.review_banner"
internal const val SIGN_IN_REVIEW_BANNER_OPEN_TAG = "sign_in_alert.review_banner.open"
internal const val COMPROMISE_CARD_TAG = "sign_in_alert.compromise_card"
internal const val COMPROMISE_CARD_REVIEW_TAG = "sign_in_alert.compromise_card.review"
internal const val COMPROMISE_CARD_HIDE_TAG = "sign_in_alert.compromise_card.hide"

/**
 * The active account's sign-in notice in the chat list: the red card while MDK has a compromise warning open on this
 * device, otherwise the neutral banner while a new sign-in waits for an answer. Both open the account's Devices
 * screen. Only the red card can be hidden, and hiding it is MDK's dismissal on this device; the banner ends when the
 * sign-ins are answered.
 */
@Composable
internal fun SignInAlertChatListNotice(appState: WhiteNoiseAppState) {
    if (!linkedDevicesAvailable()) return
    val accountRef = appState.activeAccountRef ?: return
    val snapshot = appState.signInAlerts.snapshot(accountRef) ?: return
    val scope = rememberCoroutineScope()
    var hiding by remember(accountRef) { mutableStateOf(false) }
    SignInAlertBanner(
        tier = snapshot.tier,
        warning = snapshot.warning,
        hiding = hiding,
        onReview = { appState.signInAlerts.requestDevices(accountRef) },
        onHide = {
            hiding = true
            scope.launch {
                if (!appState.hideCompromiseWarning(accountRef)) appState.present(R.string.compromise_warning_hide_failed)
                hiding = false
            }
        },
    )
}

/** The notice for [tier]; nothing for a passive account. [hiding] disables Hide while MDK takes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignInAlertBanner(
    tier: SignInAlertTier,
    warning: List<CompromiseWarningItemFfi>,
    hiding: Boolean,
    onReview: () -> Unit,
    onHide: () -> Unit,
) {
    if (tier == SignInAlertTier.Passive) return
    val compromise = tier == SignInAlertTier.Compromise
    WhiteNoiseCallout(
        modifier =
            Modifier
                .padding(
                    horizontal = WhiteNoiseSpacing.NoticeHorizontalInset,
                    vertical = WhiteNoiseSpacing.NoticeVerticalInset,
                ).testTag(if (compromise) COMPROMISE_CARD_TAG else SIGN_IN_REVIEW_BANNER_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        title = if (compromise) stringResource(R.string.compromise_warning_title) else null,
        text =
            if (compromise) {
                compromiseWarningBody(warning, steps = false)
            } else {
                stringResource(R.string.sign_in_alert_review_banner)
            },
        icon = if (compromise) R.drawable.ic_warning else R.drawable.ic_devices,
        isError = compromise,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related, Alignment.End),
        ) {
            val color = if (compromise) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
            if (compromise) {
                TextButton(onClick = onHide, enabled = !hiding, modifier = Modifier.testTag(COMPROMISE_CARD_HIDE_TAG)) {
                    Text(stringResource(R.string.compromise_warning_dismiss), color = color)
                }
                TextButton(onClick = onReview, modifier = Modifier.testTag(COMPROMISE_CARD_REVIEW_TAG)) {
                    Text(stringResource(R.string.compromise_alert_review), color = color)
                }
            } else {
                TextButton(onClick = onReview, modifier = Modifier.testTag(SIGN_IN_REVIEW_BANNER_OPEN_TAG)) {
                    Text(stringResource(R.string.sign_in_alert_review_action))
                }
            }
        }
    }
}
