package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkReminder
import dev.ipf.whitenoise.android.ui.devices.SignInAlertChatListNotice

/**
 * Persistent chat-list notices shown above the list body, below the temporary top notices: an unknown sign-in or
 * security warning first, then the active notification profile (#3601), then any account history notice, then the
 * reminder to link this device, then what is still arriving from the account's other devices. [hasChats] says whether the list has rows. Search and
 * attachment browsing hide all of them.
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
