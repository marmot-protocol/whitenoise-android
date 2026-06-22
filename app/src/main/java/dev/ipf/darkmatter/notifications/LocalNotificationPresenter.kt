package dev.ipf.darkmatter.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.MainActivity
import dev.ipf.darkmatter.R
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

class LocalNotificationPresenter(
    private val context: Context,
) {
    fun ensureChannels() {
        NotificationChannels.ensureChannels(context)
    }

    fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    // Opening / reading a conversation clears every card for it: the
    // accumulating message card, the separate reaction card, and any pending
    // group-invite card. Invites are tagged by their opaque notificationKey, not
    // the per-conversation tag, so they're found by the account + group stamped
    // into their extras at post time rather than by key.
    fun dismissConversationMessages(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return false
        val manager = NotificationManagerCompat.from(context)
        val message = LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex)
        val reaction = LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex)
        manager.cancel(message.tag, message.id)
        manager.cancel(reaction.tag, reaction.id)
        dismissInvitesForGroup(accountRef, groupIdHex)
        notificationDebug { "dismissed group=${groupIdHex.take(8)}" }
        return true
    }

    // Invite cards carry no per-conversation tag, so match them by the account +
    // group stamped into their extras and cancel each by its own (tag, id). Both
    // must match: the same group can exist in more than one local account, so the
    // group id alone would clear another account's invite for that group.
    fun dismissInvitesForGroup(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.activeNotifications }
            .getOrNull()
            ?.filter {
                val extras = it.notification.extras ?: return@filter false
                extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID) == groupIdHex &&
                    extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF) == accountRef
            }?.forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
    }

    @SuppressLint("MissingPermission")
    fun show(
        update: NotificationUpdateFfi,
        conversationTitleOverride: String? = null,
        senderNameOverride: String? = null,
        previewTextOverride: String? = null,
        reactedToPreviewOverride: String? = null,
    ): Boolean {
        val content =
            LocalNotificationFormatter.content(
                update = update,
                context = context,
                senderNameOverride = senderNameOverride,
                previewTextOverride = previewTextOverride,
                reactedToPreviewOverride = reactedToPreviewOverride,
            ) ?: run {
                notificationDebug { "skip key=${update.notificationKey.take(16)} reason=formatter" }
                return false
            }
        if (!canPostNotifications()) {
            notificationDebug { "skip key=${update.notificationKey.take(16)} reason=permission" }
            return false
        }
        // Channels are created during AppState bootstrap / runtime start
        // (AppState.bootstrap() and ensureNotificationRuntimeStarted() both
        // call ensureChannels()); we deliberately don't recreate them on
        // every show() to avoid the per-notification Binder IPC into
        // NotificationManagerService.

        // Route each notification to its per-type channel so the user's OS-level
        // sound / vibration / importance / mute choices apply per type.
        val channelId = NotificationChannelSpec.forUpdate(update).id
        val isReaction = NotificationChannelSpec.forUpdate(update) == NotificationChannelSpec.REACTIONS
        val category =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> NotificationCompat.CATEGORY_MESSAGE
                NotificationTriggerFfi.GROUP_INVITE -> NotificationCompat.CATEGORY_EVENT
            }
        val builder =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_darkmatter)
                .setContentIntent(conversationPendingIntent(update, content.notificationTag))
                .setCategory(category)
                .setWhen(update.timestampMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(redactedPublicVersion(channelId, category))
                .setSilent(false)

        when {
            // Reactions get their own self-contained card (own tag/id on the
            // reactions channel, see LocalNotificationFormatter) so they're muted
            // independently of messages. They aren't repliable, so no
            // MessagingStyle / reply / mark-read — just a plain expandable card.
            isReaction ->
                builder
                    .setContentTitle(content.title)
                    .setContentText(content.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))

            // Messages stack into one per-conversation card; invites are
            // one-off events, so keep them as a plain expandable notification.
            update.trigger == NotificationTriggerFfi.NEW_MESSAGE -> {
                builder.setStyle(messagingStyle(update, content, conversationTitleOverride))
                NotificationActions
                    .targetFromUpdate(update, content.notificationTag, content.notificationId)
                    ?.let { actionTarget ->
                        builder.addAction(replyNotificationAction(actionTarget))
                        builder.addAction(markReadNotificationAction(actionTarget))
                    }
            }

            else -> {
                builder
                    .setContentTitle(content.title)
                    .setContentText(content.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                // Stamp the invited-to account + group so accepting/declining or
                // opening that conversation can find and dismiss this card (its
                // tag is the opaque key).
                if (update.trigger == NotificationTriggerFfi.GROUP_INVITE && update.groupIdHex.isNotBlank()) {
                    builder.addExtras(
                        Bundle().apply {
                            putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, update.accountRef)
                            putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, update.groupIdHex)
                        },
                    )
                }
            }
        }

        NotificationManagerCompat.from(context).notify(content.notificationTag, content.notificationId, builder.build())
        notificationDebug {
            // Never log the title/body — they carry sender / group names (PII).
            "posted tag=${content.notificationTag.take(16)} trigger=${update.trigger} group=${update.groupIdHex.take(8)}"
        }
        return true
    }

    // Shown in place of the real card whenever the lockscreen redacts private
    // notifications. The OS can auto-generate one, but that behaviour varies by
    // OEM; supplying our own guarantees no sender, body, or group name ever
    // reaches the lockscreen — only the app name.
    private fun redactedPublicVersion(
        channelId: String,
        category: String,
    ): Notification =
        NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_darkmatter)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(category)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    fun cancel(
        notificationTag: String,
        notificationId: Int,
    ) {
        NotificationManagerCompat.from(context).cancel(notificationTag, notificationId)
        notificationDebug { "cancelled tag=${notificationTag.take(16)} id=$notificationId" }
    }

    /**
     * Re-post the (tag, id) notification carrying a RemoteInput history entry —
     * the documented "reply handled" signal that clears the system's
     * FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY. A direct-reply notification is
     * lifetime-extended by the system (API 34+) and a bare [cancel] is ignored
     * while that flag is set, so the caller must do this (and let it settle)
     * before cancelling. Returns true once the live notification was found and
     * re-posted; false if it isn't in the active set yet (caller should retry —
     * the extension is applied a beat after the reply broadcast fires).
     *
     * The re-post is rebuilt FROM the live notification (recovering its content
     * intent, MessagingStyle, category, and reply/mark-read actions) with only
     * the RemoteInput history added on top — so if the follow-up cancel is
     * dropped or delayed, the card that survives is still the functional
     * conversation card, not a blank tap-dead one.
     */
    @SuppressLint("MissingPermission")
    fun markDirectReplyHandled(
        notificationTag: String,
        notificationId: Int,
        replyText: String,
    ): Boolean {
        val active =
            runCatching {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
            }.getOrNull() ?: return false
        return runCatching {
            val resolved =
                NotificationCompat
                    .Builder(context, active.notification)
                    .setRemoteInputHistory(arrayOf(replyText))
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .build()
            NotificationManagerCompat.from(context).notify(notificationTag, notificationId, resolved)
            notificationDebug { "reply-handled re-post tag=${notificationTag.take(16)} id=$notificationId" }
            true
        }.getOrDefault(false)
    }

    // Accumulate every message from a conversation into one card. Android keys a
    // notification by (tag, id); reusing the per-conversation tag updates the
    // existing card, and MessagingStyle appends the new line to the previous
    // ones it carried — so five messages read as one entry, not five alerts.
    private fun messagingStyle(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        conversationTitleOverride: String?,
    ): NotificationCompat.MessagingStyle {
        val self =
            Person
                .Builder()
                .setName(content.selfName)
                .setKey(content.selfKey)
                .build()
        // Cap carried-forward history; the extracted style is otherwise re-serialized unbounded across Binder on every post.
        val style = NotificationCompat.MessagingStyle(self)
        existingMessagingStyle(content.notificationTag)
            ?.messages
            ?.takeLast(MAX_MESSAGE_HISTORY - 1)
            ?.forEach { style.addMessage(it) }
        style.isGroupConversation = content.isGroupConversation
        // Prefer the caller-resolved title (chat-list parity, e.g. "Group of N
        // people" for unnamed groups) over the often-empty payload group name.
        (conversationTitleOverride?.takeIf { it.isNotBlank() } ?: content.conversationTitle)?.let { style.conversationTitle = it }
        val sender =
            Person
                .Builder()
                .setName(content.senderName)
                .setKey(content.senderKey)
                .build()
        style.addMessage(content.body, update.timestampMs, sender)
        return style
    }

    private fun existingMessagingStyle(tag: String): NotificationCompat.MessagingStyle? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val existing =
            runCatching { manager.activeNotifications }
                .getOrNull()
                ?.firstOrNull { it.tag == tag && it.id == LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID }
                ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(existing.notification)
    }

    private fun replyNotificationAction(actionTarget: NotificationActionTarget): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(NotificationActions.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.message))
                .build()
        return NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_darkmatter,
                context.getString(R.string.reply),
                actionPendingIntent(actionTarget, NotificationActionKind.REPLY),
            ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadNotificationAction(actionTarget: NotificationActionTarget): NotificationCompat.Action =
        NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_darkmatter,
                context.getString(R.string.chat_row_action_mark_read),
                actionPendingIntent(actionTarget, NotificationActionKind.MARK_READ),
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun actionPendingIntent(
        actionTarget: NotificationActionTarget,
        kind: NotificationActionKind,
    ): PendingIntent {
        val actionIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                NotificationActions.applyToIntent(this, kind, actionTarget)
            }
        val mutableFlag =
            if (kind == NotificationActionKind.REPLY) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
        return PendingIntent.getBroadcast(
            context,
            NotificationActions.requestCode(kind, actionTarget.notificationTag),
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    private fun conversationPendingIntent(
        update: NotificationUpdateFfi,
        tag: String,
    ): PendingIntent {
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Key the tap target on the notification tag (per-conversation
                // for messages) so the accumulating card always reopens the
                // same conversation. PendingIntents compare by URI, not extras.
                NotificationNavigation.fromUpdate(update)?.let { target ->
                    NotificationNavigation.applyToIntent(this, target, tag)
                }
            }
        return PendingIntent.getActivity(
            context,
            NotificationNavigation.requestCode(tag),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        // Per-conversation cards share id 0; the per-conversation tag keeps them
        // distinct, so reusing (tag, 0) updates the right conversation's card.
        private const val MAX_MESSAGE_HISTORY = 25
    }
}

private inline fun notificationDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMLocalNotify", message())
}
