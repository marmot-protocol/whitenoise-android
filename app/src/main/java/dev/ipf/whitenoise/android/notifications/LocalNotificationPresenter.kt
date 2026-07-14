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
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class LocalNotificationPresenter(
    private val context: Context,
    private val shortcutPublisher: (ShortcutInfoCompat) -> Unit = { shortcut ->
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    },
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification> = { manager ->
        manager.activeNotifications
    },
) {
    private val redactedPublicVersions = ConcurrentHashMap<String, Notification>()
    private val shortcutSnapshots = ConcurrentHashMap<String, ConversationShortcutSnapshot>()
    private val shortcutLastUsed = ConcurrentHashMap<String, Long>()
    private val shortcutAccessClock = AtomicLong()
    private val tapTokens = NotificationTapTokens.create(context)

    // Conversation channels we've already created in this process, so the hot
    // post path skips the get-or-create Binder round-trip after the first post.
    private val ensuredConversationChannels = ConcurrentHashMap.newKeySet<String>()

    // Warms the avatar cache off the post path when a conversation's avatar is
    // not yet cached, so a later notification (or shortcut refresh) can attach
    // it. Never blocks notification delivery on the network.
    private val avatarWarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Replying / marking read owns the acted-on card, so cancel it before taking
    // the sibling snapshot. Both action paths share this operation so their
    // ordering and newer-sibling preservation cannot drift apart.
    fun dismissActionNotificationAndOlderSiblings(
        notificationTag: String,
        notificationId: Int,
        accountRef: String,
        groupIdHex: String,
        sinceMs: Long,
    ): Boolean {
        cancel(notificationTag, notificationId)
        return dismissConversationSiblingCardsNotNewerThan(accountRef, groupIdHex, sinceMs)
    }

    // Clear reaction, mention, and invite sibling cards, but keep any that were
    // (re)posted after [sinceMs] — a reaction, mention, or invite that landed
    // during the action window is genuinely new and must survive.
    fun dismissConversationSiblingCardsNotNewerThan(
        accountRef: String,
        groupIdHex: String,
        sinceMs: Long,
    ): Boolean {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val active = runCatching { activeNotificationsProvider(manager) }.getOrNull()?.toList().orEmpty()
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
        conversationAvatarUrl: String? = null,
        senderAvatarUrl: String? = null,
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
        // A shortcut-backed message posts on its per-conversation channel (the
        // child of whichever parent it routed to — message OR mention), so
        // Android treats it as a conversation and the user's per-conversation
        // sound/vibration applies. Locked/redacted posts and non-message cards
        // stay on the parent channel and carry no shortcut.
        val messagingShortcutId =
            if (!redactContent && decision.style == NotificationStyleChoice.Messaging) {
                conversationShortcutId(update.accountRef, update.groupIdHex)
            } else {
                null
            }
        val channelId =
            if (messagingShortcutId != null) {
                withContext(Dispatchers.Default) {
                    ensureConversationChannel(decision.channelId, messagingShortcutId)
                } ?: decision.channelId
            } else {
                decision.channelId
            }
        val builder =
            NotificationCompat
                .Builder(context, channelId)
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

        var messagingPost: MessagingPostContext? = null
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
                val (conversationAvatarBitmap, senderAvatarBitmap) =
                    if (redactContent) {
                        null to null
                    } else {
                        withContext(Dispatchers.Default) {
                            coroutineScope {
                                val conversationAvatar = async { resolveAvatarBitmap(conversationAvatarUrl) }
                                val senderAvatar = async { resolveAvatarBitmap(senderAvatarUrl) }
                                conversationAvatar.await() to senderAvatar.await()
                            }
                        }
                    }
                warmConversationAvatar(conversationAvatarUrl, alreadyCached = conversationAvatarBitmap != null)
                warmConversationAvatar(senderAvatarUrl, alreadyCached = senderAvatarBitmap != null)
                val sender = notificationSenderPerson(notificationContent, senderAvatarBitmap)
                if (!redactContent && messagingShortcutId != null) {
                    val locusId = LocusIdCompat(messagingShortcutId)
                    builder
                        .setShortcutId(messagingShortcutId)
                        .setLocusId(locusId)
                        .addPerson(sender)
                    withContext(Dispatchers.Default) {
                        publishConversationShortcut(
                            update,
                            notificationContent,
                            messagingShortcutId,
                            locusId,
                            conversationAvatarUrl,
                            conversationAvatarBitmap,
                            senderAvatarUrl,
                            senderAvatarBitmap,
                            sender,
                        )
                    }
                }
                update.messageIdHex?.takeIf { it.isNotBlank() }?.let { messageIdHex ->
                    builder.addExtras(
                        Bundle().apply {
                            putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, messageIdHex)
                        },
                    )
                }
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
                messagingPost =
                    MessagingPostContext(
                        sender = sender,
                        conversationTitleOverride = if (redactContent) null else conversationTitleOverride,
                    )
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
        withContext(Dispatchers.Default) {
            val messaging = messagingPost
            if (messaging != null) {
                ConversationCardPostSynchronizer.withLock(
                    notificationContent.notificationTag,
                    notificationContent.notificationId,
                    ConversationCardOp.SHOW_NOTIFY,
                ) {
                    val carried =
                        if (redactContent) {
                            null
                        } else {
                            existingMessagingStyle(
                                notificationContent.notificationTag,
                                notificationContent.notificationId,
                            )?.messages
                        }
                    ConversationCardPostSynchronizer.awaitTestBarrier(
                        ConversationCardOp.SHOW_NOTIFY,
                        ConversationCardBarrier.AFTER_READ,
                        notificationContent.notificationTag,
                        notificationContent.notificationId,
                    )
                    builder.setStyle(
                        messagingStyle(
                            update,
                            notificationContent,
                            messaging.conversationTitleOverride,
                            decision.historyCap,
                            carried,
                            messaging.sender,
                        ),
                    )
                    val notification = builder.build()
                    ConversationCardPostSynchronizer.awaitTestBarrier(
                        ConversationCardOp.SHOW_NOTIFY,
                        ConversationCardBarrier.BEFORE_WRITE,
                        notificationContent.notificationTag,
                        notificationContent.notificationId,
                    )
                    if (decision.replaceExistingBeforePost) {
                        notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
                    }
                    notificationManager.notify(notificationContent.notificationTag, notificationContent.notificationId, notification)
                }
            } else {
                val notification = builder.build()
                if (decision.replaceExistingBeforePost) {
                    notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
                }
                notificationManager.notify(notificationContent.notificationTag, notificationContent.notificationId, notification)
            }
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

    internal fun conversationCardMessageIdHex(
        notificationTag: String,
        notificationId: Int,
    ): String? = conversationCardMessageIdHex(activeConversationCard(notificationTag, notificationId))

    internal fun cancelRepliedConversationCardIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        repliedMessageIdHex: String?,
    ) {
        ConversationCardPostSynchronizer.withLock(
            notificationTag,
            notificationId,
            ConversationCardOp.CANCEL_IF_SAME_GENERATION,
        ) {
            val liveCardMessageIdHex = conversationCardMessageIdHex(notificationTag, notificationId)
            ConversationCardPostSynchronizer.awaitTestBarrier(
                ConversationCardOp.CANCEL_IF_SAME_GENERATION,
                ConversationCardBarrier.AFTER_READ,
                notificationTag,
                notificationId,
            )
            if (shouldCancelRepliedConversationCard(repliedMessageIdHex, liveCardMessageIdHex)) {
                NotificationManagerCompat.from(context).cancel(notificationTag, notificationId)
                notificationDebug { "cancelled tag=${notificationTag.take(16)} id=$notificationId" }
            }
        }
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
    ): Boolean =
        ConversationCardPostSynchronizer.withLock(
            notificationTag,
            notificationId,
            ConversationCardOp.MARK_REPLY_HANDLED,
        ) {
            val active =
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                }.getOrNull() ?: return@withLock false
            ConversationCardPostSynchronizer.awaitTestBarrier(
                ConversationCardOp.MARK_REPLY_HANDLED,
                ConversationCardBarrier.AFTER_READ,
                notificationTag,
                notificationId,
            )
            runCatching {
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
        sender: Person,
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
        style.addMessage(content.body, update.timestampMs, sender)
        return style
    }

    private fun activeConversationCard(
        tag: String,
        id: Int,
    ): Notification? =
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.firstOrNull { it.tag == tag && it.id == id }
                ?.notification
        }.getOrNull()

    private fun existingMessagingStyle(
        tag: String,
        id: Int,
    ): NotificationCompat.MessagingStyle? {
        val existing = activeConversationCard(tag, id) ?: return null
        if (existing.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(existing)
    }

    private fun publishConversationShortcut(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        shortcutId: String,
        locusId: LocusIdCompat,
        conversationAvatarUrl: String?,
        conversationAvatarBitmap: android.graphics.Bitmap?,
        senderAvatarUrl: String?,
        senderAvatarBitmap: android.graphics.Bitmap?,
        sender: Person,
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
                    avatarUrl = conversationAvatarUrl,
                    avatarApplied = conversationAvatarBitmap != null,
                    senderAvatarUrl = senderAvatarUrl,
                    senderAvatarApplied = senderAvatarBitmap != null,
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
                    .setIcon(conversationShortcutIcon(conversationAvatarBitmap))
                    .setIntent(intent)
                    .setLocusId(locusId)
                    .setPerson(sender)
                    .setLongLived(true)
                    .build()
            shortcutPublisher(shortcut)
            shortcutSnapshots[shortcutId] = snapshot
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
        }.onFailure {
            notificationDebug { "conversation shortcut skipped group=${update.groupIdHex.take(8)}" }
        }
    }

    // Adaptive bitmap so the People / conversation surfaces mask the avatar to a
    // circle; fall back to the launcher icon when the chat has no avatar or it
    // isn't cached yet.
    private fun conversationShortcutIcon(avatarBitmap: android.graphics.Bitmap?): IconCompat =
        if (avatarBitmap != null) {
            IconCompat.createWithAdaptiveBitmap(avatarBitmap)
        } else {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }

    private suspend fun resolveAvatarBitmap(url: String?): android.graphics.Bitmap? {
        if (url.isNullOrBlank()) return null
        AvatarImageLoader.peekBitmap(url)?.let { return it }
        // Bounded so a slow avatar host can't delay notification delivery; the
        // underlying fetch still completes and caches, upgrading the next post.
        return withTimeoutOrNull(AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS) { AvatarImageLoader.loadBitmap(url) }
    }

    private fun warmConversationAvatar(
        url: String?,
        alreadyCached: Boolean,
    ) {
        if (alreadyCached || url.isNullOrBlank()) return
        avatarWarmScope.launch { runCatching { AvatarImageLoader.load(url) } }
    }

    private fun ensureConversationChannel(
        parentChannelId: String,
        conversationShortcutId: String,
    ): String? {
        val conversationChannelId = ConversationNotificationChannels.conversationChannelId(parentChannelId, conversationShortcutId)
        if (conversationChannelId in ensuredConversationChannels) return conversationChannelId
        val created = ConversationNotificationChannels.ensureConversationChannel(context, parentChannelId, conversationShortcutId)
        if (created != null) ensuredConversationChannels.add(created)
        return created
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

private data class MessagingPostContext(
    val sender: Person,
    val conversationTitleOverride: String?,
)

private data class ConversationShortcutSnapshot(
    val shortcutId: String,
    val shortLabel: String,
    val longLabel: String,
    val notificationTag: String,
    val senderName: String,
    val senderKey: String,
    val avatarUrl: String?,
    val avatarApplied: Boolean,
    val senderAvatarUrl: String?,
    val senderAvatarApplied: Boolean,
)

internal fun notificationSenderPerson(
    content: LocalNotificationContent,
    avatarBitmap: android.graphics.Bitmap?,
): Person =
    Person
        .Builder()
        .setName(content.senderName)
        .setKey(content.senderKey)
        .apply {
            avatarBitmap?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()

private const val AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS = 2_500L
private const val EXTRA_CONTENT_REDACTED = "dev.ipf.whitenoise.android.notify.content_redacted"

private inline fun notificationDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMLocalNotify", message())
}
