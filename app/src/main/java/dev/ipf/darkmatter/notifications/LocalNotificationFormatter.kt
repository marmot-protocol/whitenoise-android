package dev.ipf.darkmatter.notifications

import android.content.Context
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi

data class LocalNotificationContent(
    val notificationTag: String,
    val notificationId: Int,
    val title: String,
    val body: String,
    val senderName: String,
    val senderKey: String,
    val selfName: String,
    val selfKey: String,
    val isGroupConversation: Boolean,
    val conversationTitle: String?,
)

object LocalNotificationFormatter {
    fun content(
        update: NotificationUpdateFfi,
        context: Context? = null,
    ): LocalNotificationContent? {
        if (update.isFromSelf) return null
        val title =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> messageTitle(update, context)
                NotificationTriggerFfi.GROUP_INVITE -> text(context, R.string.notification_group_invite, "Group invite")
            }
        val body =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> clean(update.previewText) ?: text(context, R.string.notification_new_message, "New message")
                NotificationTriggerFfi.GROUP_INVITE -> inviteBody(update, context)
            }
        return LocalNotificationContent(
            // Messages from one conversation share a per-account, per-group tag
            // so they accumulate into a single MessagingStyle card instead of N
            // independent alerts. Invites stay individual (per notification key).
            notificationTag =
                when (update.trigger) {
                    NotificationTriggerFfi.NEW_MESSAGE -> "${update.accountRef}|${update.groupIdHex}"
                    NotificationTriggerFfi.GROUP_INVITE -> update.notificationKey
                },
            notificationId = 0,
            title = title,
            body = body,
            senderName = displayName(update.sender),
            senderKey = update.sender.accountIdHex,
            selfName = displayName(update.receiver),
            selfKey = update.receiver.accountIdHex,
            isGroupConversation = !update.isDm,
            conversationTitle = if (!update.isDm) clean(update.groupName) else null,
        )
    }

    private fun messageTitle(
        update: NotificationUpdateFfi,
        context: Context?,
    ): String {
        val sender = displayName(update.sender)
        val group = clean(update.groupName)
        return when {
            group != null && !update.isDm -> text(context, R.string.notification_sender_in_group, "%1\$s in %2\$s", sender, group)
            else -> sender
        }
    }

    private fun inviteBody(
        update: NotificationUpdateFfi,
        context: Context?,
    ): String {
        val sender = displayName(update.sender)
        val group = clean(update.groupName)
        return if (group == null) {
            text(context, R.string.notification_invite_from_sender, "Invite from %1\$s", sender)
        } else {
            text(context, R.string.notification_sender_invited_you_to_group, "%1\$s invited you to %2\$s", sender, group)
        }
    }

    private fun displayName(user: NotificationUserFfi): String = clean(user.displayName) ?: IdentityFormatter.short(user.accountIdHex)

    private fun clean(value: String?): String? {
        if (value == null) return null
        return ProfileSanitizer
            .stripUnsafe(value)
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun text(
        context: Context?,
        resId: Int,
        fallback: String,
        vararg args: Any,
    ): String =
        if (context == null) {
            String.format(fallback, *args)
        } else {
            context.getString(resId, *args)
        }
}
