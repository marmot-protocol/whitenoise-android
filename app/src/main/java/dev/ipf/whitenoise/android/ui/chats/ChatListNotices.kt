package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AccountHistoryNotices
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Persistent chat-list notices shown above the list body, below the temporary top notices. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListPersistentNotices(
    appState: WhiteNoiseAppState,
    historyNotices: AccountHistoryNotices?,
    showHistoryNotice: Boolean,
) {
    historyNotices?.takeIf { showHistoryNotice }?.let { owner ->
        AccountHistoryNoticeBanner(
            dismissing = owner.dismissing,
            onDismiss = { appState.launchMutation { owner.dismissAll() } },
        )
    }
}
