package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.state.ChatNotifyMode

/** Whether one local account has silenced one member's messages inside one group (#2782). */
typealias GroupSenderMutePredicate = (accountRef: String, groupIdHex: String, senderIdHex: String) -> Boolean

object LocalNotificationPolicy {
    /**
     * Applies local conversation and sender policy to one MDK-classified update.
     *
     * A durable whole-chat mute admits only a direct mention; app lock, active
     * conversation, and member mute can still suppress it. Android permission
     * and channel eligibility remain the notification presenter's decision.
     */
    fun shouldPost(
        update: NotificationUpdateFfi,
        appInForeground: Boolean,
        activeConversationGroupIdHex: String?,
        activeConversationAccountRef: String?,
        appLockScreenVisible: Boolean,
        conversationNotifyMode: (accountRef: String, groupIdHex: String) -> ChatNotifyMode = { _, _ -> ChatNotifyMode.ALL },
        engineMuted: Boolean = false,
        senderMutedInGroup: GroupSenderMutePredicate = { _, _, _ -> false },
        categoryEnabled: (String, String, NotificationChannelSpec) -> Boolean? = { _, _, _ -> null },
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
        if (!isGlobalMembershipEvent &&
            !conversationMuteAllowsUpdate(update, engineMuted, conversationNotifyMode, categoryEnabled)
        ) {
            return false
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

    /** MDK mute admits direct mentions; otherwise the saved host mode decides. */
    private fun conversationMuteAllowsUpdate(
        update: NotificationUpdateFfi,
        engineMuted: Boolean,
        conversationNotifyMode: (accountRef: String, groupIdHex: String) -> ChatNotifyMode,
        categoryEnabled: (String, String, NotificationChannelSpec) -> Boolean?,
    ): Boolean {
        val enabled = categoryEnabled(update.accountRef, update.groupIdHex, NotificationChannelSpec.forUpdate(update))
        if (engineMuted) {
            return enabled != false &&
                update.trigger == NotificationTriggerFfi.NEW_MESSAGE && update.isMention && !update.isFromSelf
        }
        val mode = conversationNotifyMode(update.accountRef, update.groupIdHex)
        return mode != ChatNotifyMode.NONE && (enabled ?: (mode == ChatNotifyMode.ALL || update.isMention))
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
