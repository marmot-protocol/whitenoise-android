@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

internal const val DEVICE_LINK_REQUEST_NOTICE_TAG = "device_link.request_notice"
internal const val DEVICE_LINK_REQUEST_NOTICE_REVIEW_TAG = "device_link.request_notice.review"
internal const val DEVICE_LINK_REQUEST_NOTICE_DISMISS_TAG = "device_link.request_notice.dismiss"

/**
 * The chat list's notice for a link request, the only way a request reaches the screen: Review opens it over the app
 * and Not now puts it off, answering nothing. However many requests arrive there is one notice; a profile with
 * repeated requests turns it into a warning. A request on another profile names that profile, and Review starts its
 * usual profile switch. The shell's lock or another protected flow keeps Review from opening anything.
 */
@Composable
internal fun DeviceLinkRequestNotice(appState: WhiteNoiseAppState) {
    if (!linkedDevicesAvailable()) return
    val watcher = appState.deviceLinkRequests
    val request = watcher.queue.notice ?: return
    val profileName = request.accountRef.takeUnless { it == appState.activeAccountRef }?.let(appState::profileName)
    DeviceLinkRequestBanner(
        repeated = request.repeated,
        profileName = profileName,
        onReview = { watcher.open(request) },
        onNotNow = { watcher.dismiss(request) },
    )
}

/**
 * Says a device is asking to link, to this profile or to [profileName]'s, or warns that a profile's requests are
 * [repeated], above Not now and Review. Screen readers hear it when it appears or turns into the warning.
 */
@Composable
internal fun DeviceLinkRequestBanner(
    repeated: Boolean,
    profileName: String?,
    onReview: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        when {
            repeated && profileName != null ->
                stringResource(R.string.link_request_repeated_notice_other_profile, profileName)
            repeated -> stringResource(R.string.link_request_repeated_notice)
            profileName != null -> stringResource(R.string.link_request_notice_other_profile, profileName)
            else -> stringResource(R.string.link_request_notice)
        }
    DeviceLinkNoticeBanner(
        text = text,
        modifier = modifier.testTag(DEVICE_LINK_REQUEST_NOTICE_TAG),
        icon = if (repeated) R.drawable.ic_warning else null,
        announce = true,
    ) {
        TextButton(onClick = onNotNow, modifier = Modifier.testTag(DEVICE_LINK_REQUEST_NOTICE_DISMISS_TAG)) {
            Text(stringResource(R.string.link_request_not_now))
        }
        TextButton(onClick = onReview, modifier = Modifier.testTag(DEVICE_LINK_REQUEST_NOTICE_REVIEW_TAG)) {
            Text(stringResource(R.string.link_request_review))
        }
    }
}

/**
 * The chat list's linked-device notice: [text], after a warning [icon] in the error colour when there is one, above
 * its [actions], which wrap below the text at any width or font size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceLinkNoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    announce: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = amoledOutlineBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
            val textModifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = textModifier)
            }
            FlowRow(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) { actions() }
        }
    }
}

/**
 * Devices over the whole window, opened from a repeated request's warning so the user can check which devices are
 * linked and remove one. Back and the page's own back control close it and return to the app.
 */
@Composable
internal fun DeviceLinkDevicesWindow(
    appState: WhiteNoiseAppState,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LinkedDevicesScreen(appState, onBack = onClose)
    }
}
