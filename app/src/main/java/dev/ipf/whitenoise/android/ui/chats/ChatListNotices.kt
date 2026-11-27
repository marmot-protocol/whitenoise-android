package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkReminder
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkRequestNotice
import dev.ipf.whitenoise.android.ui.devices.SignInAlertChatListNotice

/**
 * Persistent notices shown above the chat list after temporary notices. Sign-in warnings appear first, followed
 * by device-link requests, the active notification profile (#3601), account history, the reminder to link this
 * device and history still arriving from other devices. Search and attachment browsing hide all of them.
 * [hasChats] says whether the list has rows.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListPersistentNotices(
    appState: WhiteNoiseAppState,
    historyNotices: AccountHistoryNotices?,
    visible: Boolean,
    hasChats: Boolean,
) {
    if (!visible) return
    SignInAlertChatListNotice(appState)
    DeviceLinkRequestNotice(appState)
    NotificationProfileChatListNotice(appState)
    historyNotices?.takeIf { it.notices.isNotEmpty() }?.let { owner ->
        AccountHistoryNoticeBanner(
            dismissing = owner.dismissing,
            onDismiss = { appState.launchMutation { owner.dismissAll() } },
        )
    }
    DeviceLinkReminder(appState)
    ChatsArrivingNotice(appState, hasChats)
}
