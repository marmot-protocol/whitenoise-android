package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.state.ChatNotifyMode

/** Whether one local account has silenced one member's messages inside one group (#2782). */
typealias GroupSenderMutePredicate = (accountRef: String, groupIdHex: String, senderIdHex: String) -> Boolean

object LocalNotificationPolicy {
    fun shouldPost(
        update: NotificationUpdateFfi,
        appInForeground: Boolean,
        activeConversationGroupIdHex: String?,
        activeConversationAccountRef: String?,
        appLockScreenVisible: Boolean,
        conversationNotifyMode: (accountRef: String, groupIdHex: String) -> ChatNotifyMode = { _, _ -> ChatNotifyMode.ALL },
        engineMuted: Boolean = false,
        senderMutedInGroup: GroupSenderMutePredicate = { _, _, _ -> false },
    ): Boolean {
        if (appLockScreenVisible) return false
        if (
            update.isDm &&
            (
                update.trigger == NotificationTriggerFfi.MADE_ADMIN ||
                    update.trigger == NotificationTriggerFfi.REMOVED_AS_ADMIN
            )
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
        if (isSenderMutedForUpdate(update, senderMutedInGroup)) return false

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

    /**
     * Whether a per-member group mute silences this update.
     *
     * Only message-derived triggers can be sender-muted, so a new message, a mention and a reaction
     * from that member go quiet while membership and admin events — the safety-critical ones — are
     * never affected. Two things fail open: a DM has no per-member dimension to mute, and an update
     * whose sender identity is absent is left alone rather than suppressed on someone else's behalf.
     */
    private fun isSenderMutedForUpdate(
        update: NotificationUpdateFfi,
        senderMutedInGroup: GroupSenderMutePredicate,
    ): Boolean {
        if (update.trigger != NotificationTriggerFfi.NEW_MESSAGE || update.isDm) return false
        val senderIdHex = update.sender.accountIdHex.trim()
        return senderIdHex.isNotEmpty() && senderMutedInGroup(update.accountRef, update.groupIdHex, senderIdHex)
    }
}
