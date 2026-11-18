@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.ownsGroupChange
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkOfferHost
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkRequestHost
import dev.ipf.whitenoise.android.ui.devices.DeviceSelfNameEffect
import dev.ipf.whitenoise.android.ui.devices.UnlinkedAccountPromptHost
import dev.ipf.whitenoise.android.ui.group.GroupChangeConfirmationDialog

/**
 * The questions the app asks over whichever screen is open: a refused group change first, then linked-device
 * requests and offers, which wait while the app is locked or a group change still needs its answer.
 */
@Composable
internal fun AppQuestionHosts(appState: WhiteNoiseAppState) {
    // A refused group change waits for the user's answer wherever it was started, even after its screen closed, but
    // only while its account and runtime are current: after a switch it is withdrawn as Wait, never shown to another.
    val groupChangeQuestion = appState.groupChangeConfirmations.pending
    val groupChangeQuestionOwned = groupChangeQuestion?.let { appState.ownsGroupChange(it.target) } == true
    LaunchedEffect(groupChangeQuestion, groupChangeQuestionOwned) {
        if (!groupChangeQuestionOwned) groupChangeQuestion?.answer(proceed = false)
    }
    if (!appState.appLock.screenVisible && groupChangeQuestion != null && groupChangeQuestionOwned) {
        GroupChangeConfirmationDialog(groupChangeQuestion, appState)
    }
    // Link requests from any signed-in profile, held back while the app is locked or a group change waits on an answer.
    if (linkedDevicesAvailable() && appState.phase == AppPhase.Ready) {
        val linkBlocked = appState.appLock.screenVisible || groupChangeQuestion != null
        DeviceLinkRequestHost(appState, blocked = linkBlocked)
        DeviceLinkOfferHost(appState, blocked = linkBlocked)
        DeviceSelfNameEffect(appState)
        UnlinkedAccountPromptHost(appState, blocked = appState.appLock.screenVisible)
    }
}
