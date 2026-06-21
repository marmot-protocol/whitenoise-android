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

data class NotificationDismissalKey(
    val tag: String,
    val id: Int,
)

object LocalNotificationFormatter {
    const val MESSAGE_NOTIFICATION_ID = 0

    // Reactions live on their own channel AND need their own stable
    // notification identity. Android keys a notification by (tag, id), not by
    // channel, so if reactions reused the per-conversation message identity a
    // reaction would mutate (or be mutated by) the normal message card and the
    // "mute reactions, keep messages" user story would break. A distinct id +
    // tag prefix keeps the two cards independent within the same conversation.
    const val REACTION_NOTIFICATION_ID = 1
    private const val REACTION_TAG_PREFIX = "reaction|"

    private val whitespaceRun = Regex("\\s+")

    // Invites stamp the account + group they're for into these extras so the
    // dismissal path can find and cancel them — their card is tagged by the
    // opaque notificationKey, which isn't reconstructable from (accountRef,
    // groupIdHex). Both are matched: the same group can exist in more than one
    // local account, so the group id alone would clear another account's invite.
    const val EXTRA_DISMISS_ACCOUNT_REF = "dev.ipf.darkmatter.notify.dismiss_account_ref"
    const val EXTRA_DISMISS_GROUP_ID = "dev.ipf.darkmatter.notify.dismiss_group_id"

    fun conversationDismissalKey(
        accountRef: String,
        groupIdHex: String,
    ): NotificationDismissalKey =
        NotificationDismissalKey(
            tag = "$accountRef|$groupIdHex",
            id = MESSAGE_NOTIFICATION_ID,
        )

    // Reaction cards live under their own (prefixed tag, REACTION_NOTIFICATION_ID)
    // identity, so dismissing a conversation has to target this key on top of
    // the message key to clear them.
    fun reactionDismissalKey(
        accountRef: String,
        groupIdHex: String,
    ): NotificationDismissalKey =
        NotificationDismissalKey(
            tag = REACTION_TAG_PREFIX + conversationDismissalKey(accountRef, groupIdHex).tag,
            id = REACTION_NOTIFICATION_ID,
        )

    /**
     * True when this update is a kind:7 reaction (a NEW_MESSAGE carrying an
     * emoji that survives sanitization). The emoji is tested through [clean],
     * not raw, so channel routing and tag/id/body selection agree on the same
     * predicate — a `reactionEmoji` of only sanitizer-stripped code points
     * (e.g. a lone variation selector) is not a reaction on either path.
     */
    fun isReaction(update: NotificationUpdateFfi): Boolean =
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE && !clean(update.reactionEmoji).isNullOrEmpty()

    fun content(
        update: NotificationUpdateFfi,
        context: Context? = null,
        // Caller-resolved sender name (AppState consults the cached profile /
        // contact name and, failing that, formats an npub). The FFI payload's
        // displayName is often null for incoming messages even when the app
        // already has a name for that pubkey, so the override is what keeps the
        // notification from falling back to a raw hex key.
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
            // independent alerts. Reactions in that same conversation get their
            // own (prefixed tag, REACTION_NOTIFICATION_ID) so they form a
            // separate card on the reactions channel and never mutate — or get
            // mutated by — the normal message card. Invites stay individual.
            notificationTag =
                when {
                    update.trigger == NotificationTriggerFfi.GROUP_INVITE -> update.notificationKey
                    isReaction(update) ->
                        REACTION_TAG_PREFIX + conversationDismissalKey(update.accountRef, update.groupIdHex).tag
                    else -> conversationDismissalKey(update.accountRef, update.groupIdHex).tag
                },
            notificationId =
                when {
                    update.trigger == NotificationTriggerFfi.GROUP_INVITE -> 0
                    isReaction(update) -> REACTION_NOTIFICATION_ID
                    else -> MESSAGE_NOTIFICATION_ID
                },
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
