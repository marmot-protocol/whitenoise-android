package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText

internal data class StartChatErrorUiState(
    val npub: String,
    val progressHex: String,
    val detail: AppText,
    val diagnosticReport: String? = null,
    val recipientName: String? = null,
    val invitation: Boolean = false,
    val title: AppText = AppText.Resource(R.string.toast_couldnt_start_chat),
    val retryGroupIdHex: String? = null,
    val missingInbox: Boolean = false,
) {
    val copyable: Boolean
        get() = !diagnosticReport.isNullOrBlank()
}
