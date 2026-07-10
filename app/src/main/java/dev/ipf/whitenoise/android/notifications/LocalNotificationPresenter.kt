package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class LocalNotificationPresenter(
    private val context: Context,
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification> = { manager ->
        manager.activeNotifications
    },
) {
    private val redactedPublicVersions = ConcurrentHashMap<String, Notification>()
    private val shortcutSnapshots = ConcurrentHashMap<String, ConversationShortcutSnapshot>()
    private val shortcutLastUsed = ConcurrentHashMap<String, Long>()
    private val shortcutAccessClock = AtomicLong()
    private val tapTokens = NotificationTapTokens.create(context)

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
    suspend fun dismissConversationMessages(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return false
        return withContext(Dispatchers.Default) {
            val manager = NotificationManagerCompat.from(context)
            val message = LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex)
            val reaction = LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex)
            val mention = LocalNotificationFormatter.mentionDismissalKey(accountRef, groupIdHex)
            manager.cancel(message.tag, message.id)
            manager.cancel(reaction.tag, reaction.id)
            manager.cancel(mention.tag, mention.id)
            dismissInvitesForGroup(accountRef, groupIdHex)
            notificationDebug { "dismissed group=${groupIdHex.take(8)}" }
            true
        }
    }

    // Invite cards carry no per-conversation tag, so match them by the account +
    // group stamped into their extras and cancel each by its own (tag, id). Both
    // must match: the same group can exist in more than one local account, so the
    // group id alone would clear another account's invite for that group.
    private suspend fun dismissInvitesForGroup(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active =
            try {
                activeNotificationsProvider(manager)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return
            }
        val inviteNotifications =
            active.filter {
                val extras = it.notification.extras ?: return@filter false
                shouldDismissInvite(
                    extraAccountRef = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                    extraGroupIdHex = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                )
            }
        val compat = NotificationManagerCompat.from(context)
        inviteNotifications.forEach {
            coroutineContext.ensureActive()
            compat.cancel(it.tag, it.id)
            it.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
        }
    }

    // Replying / marking read from the shade engaged with the conversation as it
    // stood when the action fired, not with cards that arrived afterwards. Clear
    // the reaction, mention, and invite sibling cards, but keep any that were
    // (re)posted after [sinceMs] — a reaction, mention, or invite that landed
    // during the reply's retry+settle window is genuinely new and must survive.
    // The replied/read message card is cancelled directly by the caller (it is
    // deliberately re-posted mid-window to clear the direct-reply lifetime
    // extension), so it is not matched here.
    fun dismissConversationSiblingCardsNotNewerThan(
        accountRef: String,
        groupIdHex: String,
        sinceMs: Long,
    ): Boolean {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val active = runCatching { manager.activeNotifications }.getOrNull()?.toList().orEmpty()
        val compat = NotificationManagerCompat.from(context)
        val postTimeByKey = active.associate { (it.tag to it.id) to it.postTime }
        listOf(
            LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.mentionDismissalKey(accountRef, groupIdHex),
        ).forEach { key ->
            val postTime = postTimeByKey[key.tag to key.id] ?: return@forEach
            if (postTime <= sinceMs) compat.cancel(key.tag, key.id)
        }
        active.forEach { sbn ->
            val extras = sbn.notification.extras ?: return@forEach
            val isInvite =
                shouldDismissInvite(
                    extraAccountRef = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                    extraGroupIdHex = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                    accountRef = accountRef,
                    groupIdHex = groupIdHex,
                )
            if (isInvite && sbn.postTime <= sinceMs) {
                compat.cancel(sbn.tag, sbn.id)
                sbn.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    suspend fun show(
        update: NotificationUpdateFfi,
        conversationTitleOverride: String? = null,
        senderNameOverride: String? = null,
        previewTextOverride: String? = null,
        reactedToPreviewOverride: String? = null,
        mediaKind: ReplyMediaKind = ReplyMediaKind.None,
        recipientAccountSubtext: String? = null,
        redactContent: Boolean = false,
    ): Boolean {
        val formattedContent =
            LocalNotificationFormatter.content(
                update = update,
                context = context,
                senderNameOverride = senderNameOverride,
                previewTextOverride = previewTextOverride,
                reactedToPreviewOverride = reactedToPreviewOverride,
                mediaKind = mediaKind,
                conversationTitleOverride = conversationTitleOverride,
            )
        val formatterReturnedContent = formattedContent != null
        val canPost = formatterReturnedContent && canPostNotifications()
        // Channels are created during AppState bootstrap / runtime start
        // (AppState.bootstrap() and ensureNotificationRuntimeStarted() both
        // call ensureChannels()); we deliberately don't recreate them on
        // every show() to avoid the per-notification Binder IPC into
        // NotificationManagerService.

        // Route each notification to its per-type channel so the user's OS-level
        // sound / vibration / importance / mute choices apply per type.
        val decision =
            decideNotificationPost(
                update = update,
                canPost = canPost,
                formatterReturnedContent = formatterReturnedContent,
                spec = NotificationChannelSpec.forUpdate(update),
            ) ?: run {
                val reason = if (!formatterReturnedContent) "formatter" else "permission"
                notificationDebug { "skip key=${update.notificationKey.take(16)} reason=$reason" }
                return false
            }
        val rawNotificationContent = formattedContent ?: return false
        val notificationContent =
            if (redactContent) {
                LocalNotificationFormatter.redactedContent(
                    rawNotificationContent,
                    appName = context.getString(R.string.app_name),
                    body = context.getString(R.string.notification_hidden_content),
                )
            } else {
                rawNotificationContent
            }
        val builder =
            NotificationCompat
                .Builder(context, decision.channelId)
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
                .setContentIntent(conversationPendingIntent(update, notificationContent.notificationTag))
                .setCategory(decision.category)
                .setPriority(decision.importance.toCompatPriority())
                .setWhen(update.timestampMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(redactedPublicVersion(decision.channelId, decision.category))
                .setSilent(false)
        // Name the recipient identity in the header when multi-account (#836).
        if (!redactContent && !recipientAccountSubtext.isNullOrBlank()) builder.setSubText(recipientAccountSubtext)

        when (val style = decision.style) {
            // Reactions get their own self-contained card (own tag/id on the
            // reactions channel, see LocalNotificationFormatter) so they're muted
            // independently of messages. They aren't repliable, so no
            // MessagingStyle / reply / mark-read — just a plain expandable card.
            NotificationStyleChoice.Plain ->
                builder
                    .setContentTitle(notificationContent.title)
                    .setContentText(notificationContent.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.body))

            // Messages stack into one per-conversation card; invites are
            // one-off events, so keep them as a plain expandable notification.
            NotificationStyleChoice.Messaging -> {
                // Resolve the carried-forward history off-main: activeNotifications
                // is a Binder round-trip and extractMessagingStyle re-serializes it.
                val carried =
                    if (redactContent) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            existingMessagingStyle(notificationContent.notificationTag, notificationContent.notificationId)?.messages
                        }
                    }
                if (!redactContent) {
                    conversationShortcutId(update.accountRef, update.groupIdHex)?.let { shortcutId ->
                        val locusId = LocusIdCompat(shortcutId)
                        builder
                            .setShortcutId(shortcutId)
                            .setLocusId(locusId)
                            .addPerson(senderPerson(notificationContent))
                        withContext(Dispatchers.Default) {
                            publishConversationShortcut(update, notificationContent, shortcutId, locusId)
                        }
                    }
                }
                builder.setStyle(
                    messagingStyle(
                        update,
                        notificationContent,
                        if (redactContent) null else conversationTitleOverride,
                        decision.historyCap,
                        carried,
                    ),
                )
                if (redactContent) {
                    builder.addExtras(Bundle().apply { putBoolean(EXTRA_CONTENT_REDACTED, true) })
                }
                if (!redactContent) {
                    NotificationActions
                        .targetFromUpdate(update, notificationContent.notificationTag, notificationContent.notificationId)
                        ?.let { actionTarget ->
                            decision.actions.forEach { action ->
                                when (action) {
                                    NotificationActionKind.REPLY -> builder.addAction(replyNotificationAction(actionTarget))
                                    NotificationActionKind.MARK_READ -> builder.addAction(markReadNotificationAction(actionTarget))
                                }
                            }
                        }
                }
            }

            is NotificationStyleChoice.InviteWithExtras -> {
                builder
                    .setContentTitle(notificationContent.title)
                    .setContentText(notificationContent.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.body))
                // Stamp the invited-to account + group so accepting/declining or
                // opening that conversation can find and dismiss this card (its
                // tag is the opaque key).
                builder.addExtras(
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, style.accountRef)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, style.groupIdHex)
                    },
                )
            }
        }

        val notificationManager = NotificationManagerCompat.from(context)
        val notification = builder.build()
        withContext(Dispatchers.Default) {
            if (decision.replaceExistingBeforePost) {
                notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
            }
            notificationManager.notify(notificationContent.notificationTag, notificationContent.notificationId, notification)
        }
        notificationDebug {
            // Never log the title/body — they carry sender / group names (PII).
            "posted tag=${notificationContent.notificationTag.take(16)} trigger=${update.trigger} group=${update.groupIdHex.take(8)}"
        }
        return true
    }

    private fun ChannelImportance.toCompatPriority(): Int =
        when (this) {
            ChannelImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
            ChannelImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
            ChannelImportance.LOW -> NotificationCompat.PRIORITY_LOW
        }

    // Shown in place of the real card whenever the lockscreen redacts private
    // notifications. The OS can auto-generate one, but that behaviour varies by
    // OEM; supplying our own guarantees no sender, body, or group name ever
    // reaches the lockscreen — only the app name.
    private fun redactedPublicVersion(
        channelId: String,
        category: String,
    ): Notification =
        redactedPublicVersions.getOrPut("$channelId\u0000$category") {
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
                .setContentTitle(context.getString(R.string.app_name))
                .setCategory(category)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

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
        historyCap: Int,
        carriedHistory: List<NotificationCompat.MessagingStyle.Message>?,
    ): NotificationCompat.MessagingStyle {
        val self =
            Person
                .Builder()
                .setName(content.selfName)
                .setKey(content.selfKey)
                .build()
        // Cap carried-forward history; the extracted style is otherwise re-serialized unbounded across Binder on every post.
        val style = NotificationCompat.MessagingStyle(self)
        carriedHistory
            ?.let { capNotificationHistory(it, historyCap) }
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

    private fun existingMessagingStyle(
        tag: String,
        id: Int,
    ): NotificationCompat.MessagingStyle? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val existing =
            runCatching { manager.activeNotifications }
                .getOrNull()
                ?.firstOrNull { it.tag == tag && it.id == id }
                ?: return null
        if (existing.notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(existing.notification)
    }

    private fun publishConversationShortcut(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        shortcutId: String,
        locusId: LocusIdCompat,
    ) {
        runCatching {
            val title = content.conversationTitle ?: content.title
            val snapshot =
                ConversationShortcutSnapshot(
                    shortcutId = shortcutId,
                    shortLabel = title.take(24).ifBlank { context.getString(R.string.app_name) },
                    longLabel = title,
                    notificationTag = content.notificationTag,
                    senderName = content.senderName,
                    senderKey = content.senderKey,
                )
            shortcutLastUsed[shortcutId] = shortcutAccessClock.incrementAndGet()
            if (shortcutSnapshots[shortcutId] == snapshot) {
                ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                return
            }
            pruneConversationShortcutsBeforePublish(shortcutId)
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    NotificationNavigation.fromUpdate(update)?.let { target ->
                        NotificationNavigation.applyToIntent(this, target, content.notificationTag, tapTokens.tokenFor(content.notificationTag))
                    }
                }
            val shortcut =
                ShortcutInfoCompat
                    .Builder(context, shortcutId)
                    .setShortLabel(snapshot.shortLabel)
                    .setLongLabel(snapshot.longLabel)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(intent)
                    .setLocusId(locusId)
                    .setPerson(senderPerson(content))
                    .setLongLived(true)
                    .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            shortcutSnapshots[shortcutId] = snapshot
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
        }.onFailure {
            notificationDebug { "conversation shortcut skipped group=${update.groupIdHex.take(8)}" }
        }
    }

    private fun pruneConversationShortcutsBeforePublish(shortcutId: String) {
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        if (maxShortcuts <= 0) return
        val existingConversationShortcutIds =
            ShortcutManagerCompat
                .getDynamicShortcuts(context)
                .map { it.id }
                .filter { it.startsWith(CONVERSATION_SHORTCUT_PREFIX) }
                .toSet()
        if (shortcutId in existingConversationShortcutIds || existingConversationShortcutIds.size < maxShortcuts) return
        val removeCount = existingConversationShortcutIds.size - maxShortcuts + 1
        val idsToRemove =
            conversationShortcutRemovalOrder(existingConversationShortcutIds, shortcutLastUsed, shortcutId)
                .take(removeCount)
        if (idsToRemove.isEmpty()) return
        ShortcutManagerCompat.removeDynamicShortcuts(context, idsToRemove)
        idsToRemove.forEach {
            shortcutSnapshots.remove(it)
            shortcutLastUsed.remove(it)
        }
    }

    private fun senderPerson(content: LocalNotificationContent): Person =
        Person
            .Builder()
            .setName(content.senderName)
            .setKey(content.senderKey)
            .build()

    private fun replyNotificationAction(actionTarget: NotificationActionTarget): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(NotificationActions.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.message))
                .build()
        return NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_whitenoise,
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
                R.drawable.ic_stat_whitenoise,
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
                    NotificationNavigation.applyToIntent(this, target, tag, tapTokens.tokenFor(tag))
                }
            }
        return PendingIntent.getActivity(
            context,
            NotificationNavigation.requestCode(tag),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private data class ConversationShortcutSnapshot(
    val shortcutId: String,
    val shortLabel: String,
    val longLabel: String,
    val notificationTag: String,
    val senderName: String,
    val senderKey: String,
)

private const val EXTRA_CONTENT_REDACTED = "dev.ipf.whitenoise.android.notify.content_redacted"

private inline fun notificationDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMLocalNotify", message())
}
