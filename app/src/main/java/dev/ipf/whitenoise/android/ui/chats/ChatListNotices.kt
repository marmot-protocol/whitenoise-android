@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkReminder
import dev.ipf.whitenoise.android.ui.devices.OwnDeviceChatAdmission

/**
 * The chat list's notice slot: the account's history notice when [showHistoryNotice], then the reminder to link
 * this device when [showLinkReminder]. Search and the attachment browser show neither. It also admits chats one of
 * the account's own devices added this device to, which needs no room on screen.
 */
@Composable
internal fun ChatListNotices(
    appState: WhiteNoiseAppState,
    historyNotices: AccountHistoryNotices?,
    showHistoryNotice: Boolean,
    showLinkReminder: Boolean,
) {
    historyNotices?.takeIf { showHistoryNotice }?.let { owner ->
        AccountHistoryNoticeBanner(
            dismissing = owner.dismissing,
            onDismiss = { appState.launchMutation { owner.dismissAll() } },
        )
    }
    if (showLinkReminder) DeviceLinkReminder(appState)
    OwnDeviceChatAdmission(appState)
}
