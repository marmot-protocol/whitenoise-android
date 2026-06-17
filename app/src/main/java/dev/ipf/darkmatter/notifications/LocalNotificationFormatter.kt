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
    private val whitespaceRun = Regex("\\s+")

    fun content(
        update: NotificationUpdateFfi,
        context: Context? = null,
        // Caller-resolved sender name (AppState consults the cached profile /
        // contact name and, failing that, formats an npub). The FFI payload's
        // displayName is often null for incoming messages even when the app
        // already has a name for that pubkey, so the override is what keeps the
        // notification from falling back to a raw hex key. See #206.
        senderNameOverride: String? = null,
    ): LocalNotificationContent? {
        if (update.isFromSelf) return null
        val senderName = senderName(update.sender, senderNameOverride)
        val title =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> messageTitle(update, context, senderName)
                NotificationTriggerFfi.GROUP_INVITE -> text(context, R.string.notification_group_invite, "Group invite")
            }
        val body =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> messageBody(update, context)
                NotificationTriggerFfi.GROUP_INVITE -> inviteBody(update, context, senderName)
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
            senderName = senderName,
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
        sender: String,
    ): String {
        val group = clean(update.groupName)
        return when {
            group != null && !update.isDm -> text(context, R.string.notification_sender_in_group, "%1\$s in %2\$s", sender, group)
            else -> sender
        }
    }

    private fun messageBody(
        update: NotificationUpdateFfi,
        context: Context?,
    ): String {
        val emoji = clean(update.reactionEmoji)
        if (emoji != null) {
            val reactedTo = clean(update.reactedToPreview)
            return if (reactedTo != null) {
                text(context, R.string.notification_reacted_to_message, "reacted %1\$s to: \"%2\$s\"", emoji, reactedTo)
            } else {
                text(context, R.string.notification_reacted, "reacted %1\$s", emoji)
            }
        }
        return clean(update.previewText) ?: text(context, R.string.notification_new_message, "New message")
    }

    private fun inviteBody(
        update: NotificationUpdateFfi,
        context: Context?,
        sender: String,
    ): String {
        val group = clean(update.groupName)
        return if (group == null) {
            text(context, R.string.notification_invite_from_sender, "Invite from %1\$s", sender)
        } else {
            text(context, R.string.notification_sender_invited_you_to_group, "%1\$s invited you to %2\$s", sender, group)
        }
    }

    // Sender-name priority for an incoming notification:
    //   1. caller-resolved override (profile / contact name, else npub),
    //   2. the FFI payload's own displayName (when present),
    //   3. a shortened key as the last resort.
    // The override is npub-formatted by the caller, so a raw hex pubkey only
    // ever surfaces when nothing — no name and no npub — could be resolved.
    private fun senderName(
        user: NotificationUserFfi,
        override: String?,
    ): String = clean(override) ?: displayName(user)

    private fun displayName(user: NotificationUserFfi): String = clean(user.displayName) ?: IdentityFormatter.short(user.accountIdHex)

    private fun clean(value: String?): String? {
        if (value == null) return null
        return ProfileSanitizer
            .stripUnsafe(value)
            .replace(whitespaceRun, " ")
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
