package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.state.ChatNotifyMode

object LocalNotificationPolicy {
    fun shouldPost(
        update: NotificationUpdateFfi,
        appInForeground: Boolean,
        activeConversationGroupIdHex: String?,
        activeConversationAccountRef: String?,
        appLockScreenVisible: Boolean,
        conversationNotifyMode: (accountRef: String, groupIdHex: String) -> ChatNotifyMode = { _, _ -> ChatNotifyMode.ALL },
        engineMuted: Boolean = false,
    ): Boolean {
        if (appLockScreenVisible) return false
        // MDK exposes these triggers, but #822 has not defined their Android
        // presentation yet. Reject them at the eligibility boundary instead of
        // relying on the formatter's later null-content guard.
        if (
            update.trigger == NotificationTriggerFfi.MADE_ADMIN ||
            update.trigger == NotificationTriggerFfi.REMOVED_AS_ADMIN
        ) {
            return false
        }
        val isGlobalMembershipEvent = update.trigger == NotificationTriggerFfi.REMOVED_FROM_GROUP
        // Membership events belong to an app-wide OS channel. A conversation
        // mute controls its content, not the safety-critical fact that this
        // account can no longer participate in the group.
        if (!isGlobalMembershipEvent) {
            // The engine's durable mute converges across a user's devices, so a
            // conversation muted elsewhere stays quiet here even before local
            // preferences learn about it. It is a full mute: the most restrictive
            // of it and the local notify mode wins (mentions included).
            if (engineMuted) return false
            when (conversationNotifyMode(update.accountRef, update.groupIdHex)) {
                ChatNotifyMode.ALL -> Unit
                ChatNotifyMode.MENTIONS_ONLY -> if (!update.isMention) return false
                ChatNotifyMode.NONE -> return false
            }
        }

        // Suppress only the conversation the user is actively viewing — and only
        // for the account that is viewing it. A group is shared by every local
        // account that belongs to it, so matching on the group alone would
        // silence another account's notifications while this one has the chat
        // open. Both the account and the group must match to suppress.
        return !(
            appInForeground &&
                activeConversationAccountRef == update.accountRef &&
                activeConversationGroupIdHex == update.groupIdHex
        )
    }
}
