package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReplyMediaKind

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

    const val MENTION_NOTIFICATION_ID = 2
    private const val MENTION_TAG_PREFIX = "mention|"

    const val AGENT_ACTIVITY_NOTIFICATION_ID = 3
    private const val AGENT_ACTIVITY_TAG_PREFIX = "agent-activity|"

    private val whitespaceRun = Regex("\\s+")

    // Invites stamp the account + group they're for into these extras so the
    // dismissal path can find and cancel them — their card is tagged by the
    // opaque notificationKey, which isn't reconstructable from (accountRef,
    // groupIdHex). Both are matched: the same group can exist in more than one
    // local account, so the group id alone would clear another account's invite.
    const val EXTRA_DISMISS_ACCOUNT_REF = "dev.ipf.whitenoise.android.notify.dismiss_account_ref"
    const val EXTRA_DISMISS_GROUP_ID = "dev.ipf.whitenoise.android.notify.dismiss_group_id"

    // Latest message id stamped onto the accumulating conversation card at post
    // time. Reply cleanup compares this to the replied action's target message
    // id so a card updated by a newer message survives.
    const val EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX = "dev.ipf.whitenoise.android.notify.conversation_card_message_id_hex"

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

    fun mentionDismissalKey(
        accountRef: String,
        groupIdHex: String,
    ): NotificationDismissalKey =
        NotificationDismissalKey(
            tag = MENTION_TAG_PREFIX + conversationDismissalKey(accountRef, groupIdHex).tag,
            id = MENTION_NOTIFICATION_ID,
        )

    fun agentActivityDismissalKey(
        accountRef: String,
        groupIdHex: String,
    ): NotificationDismissalKey =
        NotificationDismissalKey(
            tag = AGENT_ACTIVITY_TAG_PREFIX + conversationDismissalKey(accountRef, groupIdHex).tag,
            id = AGENT_ACTIVITY_NOTIFICATION_ID,
        )

    fun notificationDismissalKey(update: NotificationUpdateFfi): NotificationDismissalKey =
        when {
            update.trigger == NotificationTriggerFfi.GROUP_INVITE ->
                NotificationDismissalKey(update.notificationKey, MESSAGE_NOTIFICATION_ID)
            isReaction(update) -> reactionDismissalKey(update.accountRef, update.groupIdHex)
            update.isMention -> mentionDismissalKey(update.accountRef, update.groupIdHex)
            update.trafficClass == NotificationTrafficClassFfi.AGENT_ACTIVITY ->
                agentActivityDismissalKey(update.accountRef, update.groupIdHex)
            else -> conversationDismissalKey(update.accountRef, update.groupIdHex)
        }

    /**
     * True when this update is a kind:7 reaction (a NEW_MESSAGE carrying an
     * emoji that survives sanitization). The emoji is tested through [clean],
     * not raw, so channel routing and tag/id/body selection agree on the same
     * predicate — a `reactionEmoji` of only sanitizer-stripped code points
     * (e.g. a lone variation selector) is not a reaction on either path.
     */
    fun isReaction(update: NotificationUpdateFfi): Boolean =
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE && !clean(update.reactionEmoji).isNullOrEmpty()

    /** True when the caller should resolve [update.previewText] before formatting. */
    fun needsPreviewTextResolution(update: NotificationUpdateFfi): Boolean = update.trigger == NotificationTriggerFfi.NEW_MESSAGE && !isReaction(update)

    /** True when the caller should resolve [update.reactedToPreview] before formatting. */
    fun needsReactedToPreviewResolution(update: NotificationUpdateFfi): Boolean = isReaction(update)

    /**
     * The notification sub-text naming which signed-in identity received the
     * event, or null. Shown only when more than one account is signed in (#836):
     * a single-account user already knows who they are, so the label would be
     * noise. Blank labels are dropped.
     */
    fun recipientAccountSubtext(
        signedInAccountCount: Int,
        recipientLabel: String?,
    ): String? = recipientLabel?.takeIf { it.isNotBlank() && signedInAccountCount > 1 }

    fun redactedContent(
        content: LocalNotificationContent,
        appName: String,
        body: String,
    ): LocalNotificationContent =
        content.copy(
            title = appName,
            body = body,
            senderName = appName,
            senderKey = "",
            selfName = appName,
            selfKey = "",
            conversationTitle = null,
        )

    fun content(
        update: NotificationUpdateFfi,
        context: Context? = null,
        // Caller-resolved sender name (AppState consults the cached profile /
        // contact name and, failing that, formats an npub). The FFI payload's
        // displayName is often null for incoming messages even when the app
        // already has a name for that pubkey, so the override is what keeps the
        // notification from falling back to a raw hex key.
        senderNameOverride: String? = null,
        // Caller-resolved message previews. AppState flattens the same Markdown
        // AST path the chat UI uses so @npub mentions become @display-name before
        // the text reaches NotificationManager.
        previewTextOverride: String? = null,
        reactedToPreviewOverride: String? = null,
        // Caller-resolved media classification, used to describe an attachment
        // with no caption ("sent a picture") instead of the generic "New
        // message". None for text messages and for media that carries a caption
        // (the caption is shown verbatim via the preview overrides).
        mediaKind: ReplyMediaKind = ReplyMediaKind.None,
        conversationTitleOverride: String? = null,
        // AppState owns hex→npub conversion through Marmot; requiring its
        // shortener here keeps this pure formatter from duplicating bech32.
        shortNpub: (String) -> String,
    ): LocalNotificationContent? {
        if (update.isFromSelf) return null
        val senderName = senderName(update.sender, senderNameOverride, shortNpub)
        val title =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> messageTitle(update, context, senderName)
                NotificationTriggerFfi.GROUP_INVITE -> inviteTitle(context)
            }
        val body =
            boundedNotificationMessageText(
                when (update.trigger) {
                    NotificationTriggerFfi.NEW_MESSAGE ->
                        messageBody(update, context, previewTextOverride, reactedToPreviewOverride, mediaKind)
                    NotificationTriggerFfi.GROUP_INVITE -> inviteBody(update, context, senderName)
                },
            )
        val dismissalKey = notificationDismissalKey(update)
        return LocalNotificationContent(
            // Messages from one conversation share a per-account, per-group tag
            // so they accumulate into a single MessagingStyle card instead of N
            // independent alerts. Reactions and mentions in that same conversation
            // get their own prefixed identities so their channel-specific cards
            // stay independent of the ordinary message card. Invites stay individual.
            notificationTag = dismissalKey.tag,
            notificationId = dismissalKey.id,
            title = title,
            body = body,
            senderName = senderName,
            senderKey = update.sender.accountIdHex,
            selfName = displayName(update.receiver, shortNpub),
            selfKey = update.receiver.accountIdHex,
            isGroupConversation = !update.isDm,
            conversationTitle = if (!update.isDm) clean(conversationTitleOverride) ?: clean(update.groupName) else null,
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
        previewTextOverride: String?,
        reactedToPreviewOverride: String?,
        mediaKind: ReplyMediaKind,
    ): String {
        val emoji = clean(update.reactionEmoji)
        if (emoji != null) {
            val reactedTo = clean(reactedToPreviewOverride) ?: clean(update.reactedToPreview)
            return if (reactedTo != null) {
                text(context, R.string.notification_reacted_to_message, "reacted %1\$s to: \"%2\$s\"", emoji, reactedTo)
            } else {
                text(context, R.string.notification_reacted, "reacted %1\$s", emoji)
            }
        }
        // A caption (or any resolved text) always wins; only a captionless
        // attachment falls through to the type-aware label, and a non-media
        // empty message to the generic body.
        return clean(previewTextOverride)
            ?: clean(update.previewText)
            ?: mediaBody(context, mediaKind)
            ?: text(context, R.string.notification_new_message, "New message")
    }

    private fun mediaBody(
        context: Context?,
        mediaKind: ReplyMediaKind,
    ): String? =
        when (mediaKind) {
            ReplyMediaKind.Photo -> text(context, R.string.notification_sent_picture, "sent a picture")
            ReplyMediaKind.Video -> text(context, R.string.notification_sent_video, "sent a video")
            ReplyMediaKind.Voice -> text(context, R.string.notification_sent_voice_message, "sent a voice message")
            ReplyMediaKind.Document -> text(context, R.string.notification_sent_file, "sent a file")
            ReplyMediaKind.None -> null
        }

    private fun inviteTitle(context: Context?): String = text(context, R.string.notification_group_invite, "Group invite")

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
    //   3. a shortened npub supplied by AppState as the last resort.
    private fun senderName(
        user: NotificationUserFfi,
        override: String?,
        shortNpub: (String) -> String,
    ): String = clean(override) ?: displayName(user, shortNpub)

    private fun displayName(
        user: NotificationUserFfi,
        shortNpub: (String) -> String,
    ): String = clean(user.displayName) ?: shortNpub(user.accountIdHex)

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
