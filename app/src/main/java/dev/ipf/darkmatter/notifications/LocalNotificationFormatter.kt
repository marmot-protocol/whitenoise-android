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
    val groupKey: String?,
    val title: String,
    val body: String,
)

object LocalNotificationFormatter {
    fun content(update: NotificationUpdateFfi, context: Context? = null): LocalNotificationContent? {
        if (update.isFromSelf) return null
        val title = when (update.trigger) {
            NotificationTriggerFfi.NEW_MESSAGE -> messageTitle(update, context)
            NotificationTriggerFfi.GROUP_INVITE -> text(context, R.string.notification_group_invite, "Group invite")
        }
        val body = when (update.trigger) {
            NotificationTriggerFfi.NEW_MESSAGE -> clean(update.previewText) ?: text(context, R.string.notification_new_message, "New message")
            NotificationTriggerFfi.GROUP_INVITE -> inviteBody(update, context)
        }
        return LocalNotificationContent(
            notificationTag = update.notificationKey,
            notificationId = 0,
            groupKey = null,
            title = title,
            body = body,
        )
    }

    private fun messageTitle(update: NotificationUpdateFfi, context: Context?): String {
        val sender = displayName(update.sender)
        val group = clean(update.groupName)
        return when {
            group != null && !update.isDm -> text(context, R.string.notification_sender_in_group, "%1\$s in %2\$s", sender, group)
            else -> sender
        }
    }

    private fun inviteBody(update: NotificationUpdateFfi, context: Context?): String {
        val sender = displayName(update.sender)
        val group = clean(update.groupName)
        return if (group == null) {
            text(context, R.string.notification_invite_from_sender, "Invite from %1\$s", sender)
        } else {
            text(context, R.string.notification_sender_invited_you_to_group, "%1\$s invited you to %2\$s", sender, group)
        }
    }

    private fun displayName(user: NotificationUserFfi): String {
        return clean(user.displayName) ?: IdentityFormatter.short(user.accountIdHex)
    }

    private fun clean(value: String?): String? {
        if (value == null) return null
        return ProfileSanitizer.stripUnsafe(value)
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun text(context: Context?, resId: Int, fallback: String, vararg args: Any): String {
        return if (context == null) {
            String.format(fallback, *args)
        } else {
            context.getString(resId, *args)
        }
    }
}
