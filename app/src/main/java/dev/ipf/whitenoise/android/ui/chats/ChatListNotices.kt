package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * Persistent chat-list notices shown above the list body, below the temporary top notices: the active
 * notification profile (#3601), then any account history notice. Search and attachment browsing hide both.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListPersistentNotices(
    appState: WhiteNoiseAppState,
    historyNotices: AccountHistoryNotices?,
    visible: Boolean,
) {
    if (!visible) return
    NotificationProfileChatListNotice(appState)
    historyNotices?.takeIf { it.notices.isNotEmpty() }?.let { owner ->
        AccountHistoryNoticeBanner(
            dismissing = owner.dismissing,
            onDismiss = { appState.launchMutation { owner.dismissAll() } },
        )
    }
}
