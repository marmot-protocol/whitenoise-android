@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkReminder

/**
 * The chat list's notice slot: the account's history notice when [showHistoryNotice], then the reminder to link
 * this device when [showLinkReminder]. Search and the attachment browser show neither.
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
}
