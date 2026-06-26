package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationUpdateFfi

object LocalNotificationPolicy {
    fun shouldPost(
        update: NotificationUpdateFfi,
        appInForeground: Boolean,
        activeConversationGroupIdHex: String?,
        activeConversationAccountRef: String?,
    ): Boolean =
        // Suppress only the conversation the user is actively viewing — and only
        // for the account that is viewing it. A group is shared by every local
        // account that belongs to it, so matching on the group alone would
        // silence another account's notifications while this one has the chat
        // open. Both the account and the group must match to suppress.
        !(
            appInForeground &&
                activeConversationAccountRef == update.accountRef &&
                activeConversationGroupIdHex == update.groupIdHex
        )
}
