package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

private const val EXTRA_EXPANDED_SINGLE_MESSAGE_BODY =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_BODY"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_TIMESTAMP"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_SENDER"

@SuppressLint("MissingPermission")
private fun postLocalNotification(
    manager: NotificationManagerCompat,
    tag: String,
    id: Int,
    notification: Notification,
) {
    manager.notify(tag, id, notification)
}

internal fun preferredConversationShortcutTitle(
    candidate: String,
    existing: String?,
): String {
    val existingTitle = existing?.takeIf { it.isNotBlank() }
    return if (
        IdentityFormatter.isNostrIdentityFallback(candidate) &&
        existingTitle != null &&
        !IdentityFormatter.isNostrIdentityFallback(existingTitle)
    ) {
        existingTitle
    } else {
        candidate
    }
}

class LocalNotificationPresenter(
    private val context: Context,
    private val shortcutPublisher: (ShortcutInfoCompat) -> Unit = { shortcut ->
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val notificationPoster: (NotificationManagerCompat, String, Int, Notification) -> Unit =
        ::postLocalNotification,
    private val cachedAvatarBitmap: (String?) -> Bitmap? = { url ->
        url?.let(AvatarImageLoader::peekBitmap)
    },
    private val avatarBitmapResolver: suspend (String?) -> Bitmap? = ::resolveNotificationAvatarBitmap,
    private val enrichmentLauncher: (suspend () -> Unit) -> Unit = { block ->
        notificationEnrichmentScope.launch { block() }
    },
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification> = { manager ->
        manager.activeNotifications
    },
) {
    private val shortcutSnapshots = ConcurrentHashMap<String, ConversationShortcutSnapshot>()
    private val shortcutLastUsed = ConcurrentHashMap<String, Long>()
    private val shortcutAccessClock = AtomicLong()
    private val tapTokens = NotificationTapTokens.create(context)
    private val conversationVibrationPreferences = ConversationVibrationPreferences(context)

    // First used from show()'s Default-dispatcher routing block, so defer the
    // SharedPreferences-backed construction instead of adding disk-backed work
    // to AppState's main-thread initialization.
    private val conversationNotificationRouting by lazy { ConversationNotificationRouting(context) }

    fun ensureChannels() {
        NotificationChannels.ensureChannels(context)
    }

    fun hideConversationShortcutsFromDirectShare() {
        hideConversationShortcutsFromDirectShare(context)
        shortcutSnapshots.clear()
        shortcutLastUsed.clear()
    }

    fun clearConversationShortcutsForAccount(accountRef: String) {
        clearConversationShortcutsForAccount(context, accountRef)
        val accountScope = conversationShortcutAccountScope(accountRef) ?: return
        shortcutSnapshots
            .filterValues { it.accountScope == accountScope }
            .keys
            .forEach { shortcutId ->
                shortcutSnapshots.remove(shortcutId)
                shortcutLastUsed.remove(shortcutId)
            }
    }

    fun canPostNotifications(): Boolean = notificationPermissionGranted(context)

    // Opening / reading a conversation clears every card for it: the
    // accumulating message card, separate typed sibling cards, and any pending
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
            val agentActivity = LocalNotificationFormatter.agentActivityDismissalKey(accountRef, groupIdHex)
            listOf(message, reaction, mention, agentActivity).forEach { key ->
                cancelSynchronized(manager, key.tag, key.id)
            }
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
            ConversationCardPostSynchronizer.withLock(
                it.tag.orEmpty(),
                it.id,
                ConversationCardOp.DISMISS_CANCEL,
            ) {
                val live = activeNotification(manager, it.tag, it.id) ?: return@withLock
                val extras = live.notification.extras ?: return@withLock
                if (
                    shouldDismissInvite(
                        extraAccountRef = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                        extraGroupIdHex = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                    )
                ) {
                    compat.cancel(live.tag, live.id)
                    live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                }
            }
        }
    }

    // Replying / marking read owns the acted-on card, so cancel it before taking
    // the sibling snapshot. Both action paths share this operation so their
    // ordering and newer-sibling preservation cannot drift apart.
    fun dismissActionNotificationAndOlderSiblings(
        notificationTag: String,
        notificationId: Int,
        actedMessageIdHex: String?,
        accountRef: String,
        groupIdHex: String,
        sinceMs: Long,
    ): Boolean {
        cancelConversationCardIfSameGeneration(notificationTag, notificationId, actedMessageIdHex)
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
        val compat = NotificationManagerCompat.from(context)
        listOf(
            LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.mentionDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.agentActivityDismissalKey(accountRef, groupIdHex),
        ).forEach { key ->
            cancelSynchronizedNotNewerThan(manager, compat, key.tag, key.id, sinceMs)
        }
        val active = runCatching { activeNotificationsProvider(manager) }.getOrNull()?.toList().orEmpty()
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
                ConversationCardPostSynchronizer.withLock(
                    sbn.tag.orEmpty(),
                    sbn.id,
                    ConversationCardOp.DISMISS_CANCEL,
                ) {
                    val live = activeNotification(manager, sbn.tag, sbn.id) ?: return@withLock
                    val liveExtras = live.notification.extras ?: return@withLock
                    if (
                        live.postTime <= sinceMs &&
                        shouldDismissInvite(
                            extraAccountRef =
                                liveExtras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                            extraGroupIdHex =
                                liveExtras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                            accountRef = accountRef,
                            groupIdHex = groupIdHex,
                        )
                    ) {
                        compat.cancel(live.tag, live.id)
                        live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                    }
                }
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
        directShareEligible: Boolean = false,
        conversationAvatarUrl: String? = null,
        senderAvatarUrl: String? = null,
        silentUpdate: Boolean = false,
        isPostStillAllowed: () -> Boolean = { true },
        shortNpub: (String) -> String,
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
                shortNpub = shortNpub,
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
        return ConversationCardPostSynchronizer.withRegisteredShow(
            notificationContent.notificationTag,
            notificationContent.notificationId,
        ) { showToken ->
            ConversationCardPostSynchronizer.awaitTestBarrier(
                ConversationCardOp.SHOW_NOTIFY,
                ConversationCardBarrier.AFTER_REGISTER,
                notificationContent.notificationTag,
                notificationContent.notificationId,
            )
            if (!isPostStillAllowed()) return@withRegisteredShow false
            // Ordinary messages keep their required People/conversation child.
            // Other event types inherit the stable global channel until this
            // chat has an explicit or legacy custom override.
            val channelShortcutId =
                if (!redactContent) {
                    conversationShortcutId(update.accountRef, update.groupIdHex)
                } else {
                    null
                }
            val messagingShortcutId = channelShortcutId.takeIf { decision.style == NotificationStyleChoice.Messaging }
            val vibrationPattern =
                if (
                    decision.channelId == NotificationChannelSpec.DIRECT_MESSAGES.id ||
                    decision.channelId == NotificationChannelSpec.GROUP_MESSAGES.id
                ) {
                    conversationVibrationPreferences.pattern(update.accountRef, update.groupIdHex)
                } else {
                    ConversationVibrationPattern.SYSTEM_DEFAULT
                }
            val channelId =
                withContext(Dispatchers.Default) {
                    conversationNotificationRouting
                        .resolveForPost(
                            channel = NotificationChannelSpec.forUpdate(update),
                            conversationShortcutId = channelShortcutId,
                            conversationTitle = conversationTitleOverride,
                            primaryVibrationPattern = vibrationPattern,
                        ).channelId
                }
            val builder =
                NotificationCompat
                    .Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_stat_whitenoise)
                    .setContentIntent(conversationPendingIntent(update, notificationContent.notificationTag))
                    .setCategory(decision.category)
                    .setPriority(decision.importance.toCompatPriority())
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(silentUpdate)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setSilent(silentUpdate)
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
                            cachedAvatarBitmap(conversationAvatarUrl) to cachedAvatarBitmap(senderAvatarUrl)
                        }
                    val sender =
                        notificationSenderPerson(
                            notificationContent,
                            senderAvatarBitmap
                                ?: if (redactContent) {
                                    null
                                } else {
                                    notificationMonogramBitmap(
                                        notificationContent.senderName,
                                        notificationContent.senderKey,
                                    )
                                },
                        )
                    if (!redactContent && messagingShortcutId != null) {
                        val locusId = LocusIdCompat(messagingShortcutId)
                        builder
                            .setShortcutId(messagingShortcutId)
                            .setLocusId(locusId)
                            .addPerson(sender)
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
                        val quickReactions =
                            if (decision.actions.contains(NotificationActionKind.REACT)) {
                                withContext(Dispatchers.Default) { notificationQuickReactionChoices(context) }
                            } else {
                                emptyList()
                            }
                        NotificationActions
                            .targetFromUpdate(update, notificationContent.notificationTag, notificationContent.notificationId)
                            ?.let { actionTarget ->
                                decision.actions.forEach { action ->
                                    when (action) {
                                        NotificationActionKind.REPLY -> builder.addAction(replyNotificationAction(actionTarget))
                                        NotificationActionKind.REACT ->
                                            quickReactions.forEach { reaction ->
                                                builder.addAction(reactionNotificationAction(actionTarget, reaction))
                                            }
                                        NotificationActionKind.MARK_READ -> builder.addAction(markReadNotificationAction(actionTarget))
                                    }
                                }
                            }
                    }
                    messagingPost =
                        MessagingPostContext(
                            sender = sender,
                            conversationTitleOverride = if (redactContent) null else conversationTitleOverride,
                            shortcutId = messagingShortcutId,
                            conversationAvatarUrl = conversationAvatarUrl,
                            conversationAvatarBitmap = conversationAvatarBitmap,
                            senderAvatarUrl = senderAvatarUrl,
                            senderAvatarBitmap = senderAvatarBitmap,
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

            messagingPost?.let { messaging ->
                withContext(Dispatchers.Default) {
                    publishInitialConversationShortcut(
                        update = update,
                        content = notificationContent,
                        messaging = messaging,
                        directShareEligible = directShareEligible,
                    )
                }
            }
            val notificationManager = NotificationManagerCompat.from(context)
            val posted =
                withContext(Dispatchers.Default) {
                    val messaging = messagingPost
                    if (messaging != null) {
                        ConversationCardPostSynchronizer.withLock(
                            notificationContent.notificationTag,
                            notificationContent.notificationId,
                            ConversationCardOp.SHOW_NOTIFY,
                        ) {
                            if (
                                !isPostStillAllowed() ||
                                !ConversationCardPostSynchronizer.isShowNotDismissed(showToken)
                            ) {
                                return@withLock false
                            }
                            val carried =
                                if (redactContent) {
                                    null
                                } else {
                                    existingConversationMessages(
                                        notificationContent.notificationTag,
                                        notificationContent.notificationId,
                                    )
                                }
                            ConversationCardPostSynchronizer.awaitTestBarrier(
                                ConversationCardOp.SHOW_NOTIFY,
                                ConversationCardBarrier.AFTER_READ,
                                notificationContent.notificationTag,
                                notificationContent.notificationId,
                            )
                            val presentationTimestampMs = nowMillis()
                            stampPresentationTime(builder, decision.channelId, decision.category, presentationTimestampMs)
                            if (
                                shouldUseExpandedSingleMessageStyle(
                                    body = notificationContent.body,
                                    carriedMessageCount = carried.orEmpty().size,
                                    redactContent = redactContent,
                                )
                            ) {
                                builder
                                    .setContentTitle(notificationContent.title)
                                    .setContentText(notificationContent.body)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.body))
                                    .addExtras(
                                        Bundle().apply {
                                            putCharSequence(
                                                EXTRA_EXPANDED_SINGLE_MESSAGE_BODY,
                                                notificationContent.body,
                                            )
                                            putLong(EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP, presentationTimestampMs)
                                            putBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER, messaging.sender.toBundle())
                                        },
                                    )
                            } else {
                                builder.setStyle(
                                    messagingStyle(
                                        notificationContent,
                                        messaging.conversationTitleOverride,
                                        decision.historyCap,
                                        carried,
                                        messaging.sender,
                                        presentationTimestampMs,
                                    ),
                                )
                            }
                            val notification = builder.build()
                            ConversationCardPostSynchronizer.awaitTestBarrier(
                                ConversationCardOp.SHOW_NOTIFY,
                                ConversationCardBarrier.BEFORE_WRITE,
                                notificationContent.notificationTag,
                                notificationContent.notificationId,
                            )
                            val firstPostSucceeded =
                                postNotificationSafely(
                                    notificationManager,
                                    notificationContent.notificationTag,
                                    notificationContent.notificationId,
                                    notification,
                                )
                            if (firstPostSucceeded) {
                                true
                            } else {
                                notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
                                if (carried.isNullOrEmpty()) {
                                    false
                                } else {
                                    builder.setStyle(
                                        messagingStyle(
                                            notificationContent,
                                            messaging.conversationTitleOverride,
                                            decision.historyCap,
                                            carriedHistory = null,
                                            sender = messaging.sender,
                                            newMessageTimestampMs = presentationTimestampMs,
                                        ),
                                    )
                                    val cleanNotification = builder.build()
                                    val retrySucceeded =
                                        postNotificationSafely(
                                            notificationManager,
                                            notificationContent.notificationTag,
                                            notificationContent.notificationId,
                                            cleanNotification,
                                        )
                                    if (!retrySucceeded) {
                                        notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
                                    }
                                    retrySucceeded
                                }
                            }
                        }
                    } else {
                        ConversationCardPostSynchronizer.withLock(
                            notificationContent.notificationTag,
                            notificationContent.notificationId,
                            ConversationCardOp.SHOW_NOTIFY,
                        ) {
                            if (
                                !isPostStillAllowed() ||
                                !ConversationCardPostSynchronizer.isShowNotDismissed(showToken)
                            ) {
                                return@withLock false
                            }
                            val presentationTimestampMs = nowMillis()
                            stampPresentationTime(builder, decision.channelId, decision.category, presentationTimestampMs)
                            val notification = builder.build()
                            val succeeded =
                                postNotificationSafely(
                                    notificationManager,
                                    notificationContent.notificationTag,
                                    notificationContent.notificationId,
                                    notification,
                                )
                            if (!succeeded) {
                                notificationManager.cancel(notificationContent.notificationTag, notificationContent.notificationId)
                            }
                            succeeded
                        }
                    }
                }
            if (!posted) return@withRegisteredShow false
            messagingPost?.takeUnless { redactContent }?.let { messaging ->
                dispatchMessagingEnrichment(
                    update = update,
                    content = notificationContent,
                    messaging = messaging,
                    showToken = showToken,
                    directShareEligible = directShareEligible,
                    isPostStillAllowed = isPostStillAllowed,
                )
            }
            notificationDebug {
                // Never log the title/body — they carry sender / group names (PII).
                "posted tag=${notificationContent.notificationTag.take(16)} trigger=${update.trigger} group=${update.groupIdHex.take(8)}"
            }
            true
        }
    }

    private fun publishInitialConversationShortcut(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        directShareEligible: Boolean,
    ) {
        val shortcutId = messaging.shortcutId ?: return
        if (shortcutSnapshots.containsKey(shortcutId)) return
        publishConversationShortcut(
            update = update,
            content = content,
            shortcutId = shortcutId,
            locusId = LocusIdCompat(shortcutId),
            conversationAvatarUrl = messaging.conversationAvatarUrl,
            conversationAvatarBitmap = messaging.conversationAvatarBitmap,
            senderAvatarUrl = messaging.senderAvatarUrl,
            senderAvatarBitmap = messaging.senderAvatarBitmap,
            sender = messaging.sender,
            directShareEligible = directShareEligible,
        )
    }

    private suspend fun dispatchMessagingEnrichment(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        showToken: ConversationCardShowToken,
        directShareEligible: Boolean,
        isPostStillAllowed: () -> Boolean,
    ) {
        val enrich: suspend () -> Unit = {
            enrichMessagingNotification(
                update = update,
                content = content,
                messaging = messaging,
                showToken = showToken,
                directShareEligible = directShareEligible,
                isPostStillAllowed = isPostStillAllowed,
            )
        }
        if (messaging.requiresRemoteAvatarResolution) {
            launchMessagingEnrichment(content.notificationTag, showToken, enrich)
        } else {
            enrich()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchMessagingEnrichment(
        notificationTag: String,
        showToken: ConversationCardShowToken,
        enrich: suspend () -> Unit,
    ) {
        if (!ConversationCardPostSynchronizer.retainShow(showToken)) return
        val released = AtomicBoolean(false)
        val releaseShow = {
            if (released.compareAndSet(false, true)) {
                ConversationCardPostSynchronizer.releaseShow(showToken)
            }
        }
        try {
            enrichmentLauncher {
                try {
                    enrich()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    notificationDebug {
                        "enrichment failed tag=${notificationTag.take(16)} " +
                            "type=${failure.javaClass.simpleName}"
                    }
                } finally {
                    releaseShow()
                }
            }
        } catch (cancellation: CancellationException) {
            releaseShow()
            throw cancellation
        } catch (failure: RuntimeException) {
            releaseShow()
            notificationDebug {
                "enrichment launch failed tag=${notificationTag.take(16)} " +
                    "type=${failure.javaClass.simpleName}"
            }
        }
    }

    private suspend fun enrichMessagingNotification(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        showToken: ConversationCardShowToken,
        directShareEligible: Boolean,
        isPostStillAllowed: () -> Boolean,
    ) {
        val (conversationAvatarBitmap, senderAvatarBitmap) =
            resolveMessagingAvatars(messaging)
        if (!isPostStillAllowed() || !ConversationCardPostSynchronizer.isShowCurrent(showToken)) return

        val enrichedSender =
            notificationSenderPerson(
                content,
                senderAvatarBitmap ?: notificationMonogramBitmap(content.senderName, content.senderKey),
            )
        postEnrichedMessagingNotification(
            update = update,
            content = content,
            messaging = messaging,
            showToken = showToken,
            directShareEligible = directShareEligible,
            isPostStillAllowed = isPostStillAllowed,
            conversationAvatarBitmap = conversationAvatarBitmap,
            senderAvatarBitmap = senderAvatarBitmap,
            enrichedSender = enrichedSender,
        )
    }

    private suspend fun resolveMessagingAvatars(messaging: MessagingPostContext): Pair<Bitmap?, Bitmap?> =
        withContext(Dispatchers.Default) {
            coroutineScope {
                val conversationAvatar =
                    async {
                        messaging.conversationAvatarBitmap
                            ?: avatarBitmapResolver(messaging.conversationAvatarUrl)
                    }
                val senderAvatar =
                    async {
                        messaging.senderAvatarBitmap
                            ?: avatarBitmapResolver(messaging.senderAvatarUrl)
                    }
                conversationAvatar.await() to senderAvatar.await()
            }
        }

    private suspend fun postEnrichedMessagingNotification(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        showToken: ConversationCardShowToken,
        directShareEligible: Boolean,
        isPostStillAllowed: () -> Boolean,
        conversationAvatarBitmap: Bitmap?,
        senderAvatarBitmap: Bitmap?,
        enrichedSender: Person,
    ) {
        ConversationCardPostSynchronizer.withLock(
            content.notificationTag,
            content.notificationId,
            ConversationCardOp.SHOW_ENRICH,
        ) {
            if (!isPostStillAllowed() || !ConversationCardPostSynchronizer.isShowCurrent(showToken)) {
                return@withLock
            }
            val active = activeConversationCard(content.notificationTag, content.notificationId) ?: return@withLock
            val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
            if (
                expectedMessageId != null &&
                conversationCardMessageIdHex(active) != expectedMessageId
            ) {
                return@withLock
            }
            messaging.shortcutId?.let { shortcutId ->
                publishConversationShortcut(
                    update = update,
                    content = content,
                    shortcutId = shortcutId,
                    locusId = LocusIdCompat(shortcutId),
                    conversationAvatarUrl = messaging.conversationAvatarUrl,
                    conversationAvatarBitmap = conversationAvatarBitmap,
                    senderAvatarUrl = messaging.senderAvatarUrl,
                    senderAvatarBitmap = senderAvatarBitmap,
                    sender = enrichedSender,
                    directShareEligible = directShareEligible,
                )
            }
            val avatarChanged =
                conversationAvatarBitmap !== messaging.conversationAvatarBitmap ||
                    senderAvatarBitmap !== messaging.senderAvatarBitmap
            if (!avatarChanged) return@withLock
            val enrichedStyle = enrichedMessagingStyle(active, enrichedSender) ?: return@withLock
            val enriched = buildEnrichedMessagingNotification(active, enrichedStyle)
            postNotificationSafely(
                NotificationManagerCompat.from(context),
                content.notificationTag,
                content.notificationId,
                enriched,
            )
        }
    }

    private fun buildEnrichedMessagingNotification(
        active: Notification,
        enrichedStyle: NotificationCompat.MessagingStyle,
    ): Notification =
        NotificationCompat
            .Builder(context, active)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setStyle(enrichedStyle)
            .build()

    private fun enrichedMessagingStyle(
        notification: Notification,
        enrichedSender: Person,
    ): NotificationCompat.MessagingStyle? {
        val existing =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?: return null
        val enriched = NotificationCompat.MessagingStyle(existing.user)
        enriched.isGroupConversation = existing.isGroupConversation
        existing.conversationTitle?.let { enriched.conversationTitle = it }
        existing.messages.forEach { message ->
            val person =
                message.person
                    ?.takeIf { it.key == enrichedSender.key }
                    ?.let { enrichedSender }
                    ?: message.person
            enriched.addMessage(
                NotificationCompat.MessagingStyle.Message(message.text, message.timestamp, person).also { copy ->
                    val mimeType = message.dataMimeType
                    val dataUri = message.dataUri
                    if (mimeType != null && dataUri != null) copy.setData(mimeType, dataUri)
                },
            )
        }
        existing.historicMessages.forEach { message -> enriched.addHistoricMessage(message) }
        return enriched
    }

    private fun postNotificationSafely(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
        notification: Notification,
    ): Boolean =
        try {
            notificationPoster(manager, tag, id, notification)
            true
        } catch (exception: RuntimeException) {
            notificationDebug {
                "post failed tag=${tag.take(16)} type=${exception.javaClass.simpleName}"
            }
            false
        }

    private fun cancelSynchronized(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
    ) {
        ConversationCardPostSynchronizer.withLock(tag, id, ConversationCardOp.DISMISS_CANCEL) {
            ConversationCardPostSynchronizer.markDismissed(tag, id)
            manager.cancel(tag, id)
        }
    }

    private fun cancelSynchronizedNotNewerThan(
        manager: NotificationManager,
        compat: NotificationManagerCompat,
        tag: String,
        id: Int,
        sinceMs: Long,
    ) {
        ConversationCardPostSynchronizer.withLock(tag, id, ConversationCardOp.DISMISS_CANCEL) {
            val live = activeNotification(manager, tag, id) ?: return@withLock
            if (live.postTime <= sinceMs) compat.cancel(tag, id)
        }
    }

    private fun activeNotification(
        manager: NotificationManager,
        tag: String?,
        id: Int,
    ): StatusBarNotification? =
        try {
            activeNotificationsProvider(manager).firstOrNull { it.tag == tag && it.id == id }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    internal fun isGroupInviteNotificationActive(update: NotificationUpdateFfi): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active =
            manager?.let {
                activeNotification(it, update.notificationKey, LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
            }
        val extras = active?.notification?.extras
        return update.trigger == NotificationTriggerFfi.GROUP_INVITE &&
            extras != null &&
            shouldDismissInvite(
                extraAccountRef = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                extraGroupIdHex = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                accountRef = update.accountRef,
                groupIdHex = update.groupIdHex,
            )
    }

    private fun ChannelImportance.toCompatPriority(): Int =
        when (this) {
            ChannelImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
            ChannelImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
            ChannelImportance.LOW -> NotificationCompat.PRIORITY_LOW
        }

    private fun stampPresentationTime(
        builder: NotificationCompat.Builder,
        channelId: String,
        category: String,
        presentationTimestampMs: Long,
    ) {
        builder.setWhen(presentationTimestampMs)
        builder.setPublicVersion(redactedPublicVersion(channelId, category, presentationTimestampMs))
    }

    // Shown in place of the real card whenever the lockscreen redacts private
    // notifications. The OS can auto-generate one, but that behaviour varies by
    // OEM; supplying our own guarantees no sender, body, or group name ever
    // reaches the lockscreen — it carries only the app name and the generic
    // hidden-content message.
    private fun redactedPublicVersion(
        channelId: String,
        category: String,
        presentationTimestampMs: Long,
    ): Notification =
        NotificationCompat
            .Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.app_name))
            // A body line even in the redacted variant — without it the shade
            // shows a bare icon+header shell when the OS hides sensitive
            // content, which reads as a broken notification.
            .setContentText(context.getString(R.string.notification_hidden_content))
            .setCategory(category)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(presentationTimestampMs)
            .build()

    fun cancel(
        notificationTag: String,
        notificationId: Int,
    ) {
        cancelSynchronized(NotificationManagerCompat.from(context), notificationTag, notificationId)
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
        cancelConversationCardIfSameGeneration(notificationTag, notificationId, repliedMessageIdHex)
    }

    internal fun cancelConversationCardIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        actedMessageIdHex: String?,
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
            if (shouldCancelRepliedConversationCard(actedMessageIdHex, liveCardMessageIdHex)) {
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

    /**
     * Re-post the (tag, id) conversation card after a direct reply failed
     * terminally, clearing the direct-reply lifetime extension the same way
     * [markDirectReplyHandled] does — but stamping [failureNotice] into the
     * RemoteInput history instead of the reply text, so the card reads as
     * not-sent while keeping its original actions (the user can tap Reply and
     * retype). Skipped when the live card is a newer generation than the
     * replied message: that newer post already cleared the extension, and a
     * failure line rendered over newer messages would mislabel them.
     */
    @SuppressLint("MissingPermission")
    fun markDirectReplyFailed(
        notificationTag: String,
        notificationId: Int,
        repliedMessageIdHex: String?,
        failureNotice: String,
    ): Boolean =
        ConversationCardPostSynchronizer.withLock(
            notificationTag,
            notificationId,
            ConversationCardOp.MARK_REPLY_FAILED,
        ) {
            val active =
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                }.getOrNull() ?: return@withLock false
            val liveCardMessageIdHex = conversationCardMessageIdHex(active.notification)
            if (!shouldCancelRepliedConversationCard(repliedMessageIdHex, liveCardMessageIdHex)) {
                return@withLock false
            }
            runCatching {
                val resolved =
                    NotificationCompat
                        .Builder(context, active.notification)
                        .setRemoteInputHistory(arrayOf(failureNotice))
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build()
                NotificationManagerCompat.from(context).notify(notificationTag, notificationId, resolved)
                notificationDebug { "reply-failed re-post tag=${notificationTag.take(16)} id=$notificationId" }
                true
            }.getOrDefault(false)
        }

    // Accumulate every message from a conversation into one card. Android keys a
    // notification by (tag, id); reusing the per-conversation tag updates the
    // existing card, and MessagingStyle appends the new line to the previous
    // ones it carried — so five messages read as one entry, not five alerts.
    private fun messagingStyle(
        content: LocalNotificationContent,
        conversationTitleOverride: String?,
        historyCap: Int,
        carriedHistory: List<NotificationCompat.MessagingStyle.Message>?,
        sender: Person,
        newMessageTimestampMs: Long,
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
            ?.forEach { message ->
                style.addMessage(
                    NotificationCompat.MessagingStyle
                        .Message(
                            boundedNotificationMessageText(message.text ?: ""),
                            message.timestamp,
                            message.person,
                        ).also { bounded ->
                            val mimeType = message.dataMimeType
                            val dataUri = message.dataUri
                            if (mimeType != null && dataUri != null) bounded.setData(mimeType, dataUri)
                        },
                )
            }
        style.isGroupConversation = content.isGroupConversation
        // Prefer the caller-resolved title (chat-list parity, e.g. "Group of N
        // people" for unnamed groups) over the often-empty payload group name.
        (conversationTitleOverride?.takeIf { it.isNotBlank() } ?: content.conversationTitle)?.let { style.conversationTitle = it }
        style.addMessage(content.body, newMessageTimestampMs, sender)
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

    private fun existingConversationMessages(
        tag: String,
        id: Int,
    ): List<NotificationCompat.MessagingStyle.Message>? =
        activeConversationCard(tag, id)
            ?.takeUnless { it.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true }
            ?.let { existing ->
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(existing)
                    ?.messages
                    ?: expandedSingleMessage(existing.extras)
            }

    private fun expandedSingleMessage(extras: Bundle?): List<NotificationCompat.MessagingStyle.Message>? =
        extras?.let { bundle ->
            val body = bundle.getCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_BODY)
            val senderBundle = bundle.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
            val timestamp = bundle.getLong(EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP, Long.MIN_VALUE)
            if (body == null || senderBundle == null || timestamp == Long.MIN_VALUE) {
                null
            } else {
                listOf(
                    NotificationCompat.MessagingStyle.Message(
                        boundedNotificationMessageText(body),
                        timestamp,
                        Person.fromBundle(senderBundle),
                    ),
                )
            }
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
        directShareEligible: Boolean,
    ) {
        runCatching {
            val candidateTitle = content.conversationTitle ?: content.title
            val existingTitle =
                shortcutSnapshots[shortcutId]?.longLabel
                    ?: ShortcutManagerCompat
                        .getDynamicShortcuts(context)
                        .firstOrNull { it.id == shortcutId }
                        ?.longLabel
                        ?.toString()
            val title = preferredConversationShortcutTitle(candidateTitle, existingTitle)
            val accountScope = conversationShortcutAccountScope(update.accountRef) ?: return
            val snapshot =
                ConversationShortcutSnapshot(
                    shortcutId = shortcutId,
                    accountScope = accountScope,
                    shortLabel = title.take(24).ifBlank { context.getString(R.string.app_name) },
                    longLabel = title,
                    notificationTag = content.notificationTag,
                    senderName = content.senderName,
                    senderKey = content.senderKey,
                    avatarUrl = conversationAvatarUrl,
                    avatarApplied = conversationAvatarBitmap != null,
                    senderAvatarUrl = senderAvatarUrl,
                    senderAvatarApplied = senderAvatarBitmap != null,
                    directShareEligible = directShareEligible,
                )
            shortcutLastUsed[shortcutId] = shortcutAccessClock.incrementAndGet()
            if (shortcutSnapshots[shortcutId] == snapshot) {
                ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                return
            }
            pruneConversationShortcutsBeforePublish(shortcutId)
            val intent = conversationShortcutOpenIntent(update, content)
            val shortcut =
                conversationShortcutInfo(
                    shortcutId = shortcutId,
                    snapshot = snapshot,
                    intent = intent,
                    locusId = locusId,
                    sender = sender,
                    conversationAvatarBitmap = conversationAvatarBitmap,
                    directShareEligible = directShareEligible,
                )
            shortcutPublisher(shortcut)
            shortcutSnapshots[shortcutId] = snapshot
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
        }.onFailure {
            notificationDebug { "conversation shortcut skipped group=${update.groupIdHex.take(8)}" }
        }
    }

    private fun conversationShortcutOpenIntent(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            NotificationNavigation.fromUpdate(update)?.let { target ->
                NotificationNavigation.applyToIntent(this, target, content.notificationTag, tapTokens.tokenFor(content.notificationTag))
            }
        }

    private fun conversationShortcutInfo(
        shortcutId: String,
        snapshot: ConversationShortcutSnapshot,
        intent: Intent,
        locusId: LocusIdCompat,
        sender: Person,
        conversationAvatarBitmap: android.graphics.Bitmap?,
        directShareEligible: Boolean,
    ): ShortcutInfoCompat {
        val builder =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel(snapshot.shortLabel)
                .setLongLabel(snapshot.longLabel)
                .setIcon(
                    notificationConversationIcon(
                        title = snapshot.longLabel,
                        seed = snapshot.shortcutId,
                        avatarBitmap = conversationAvatarBitmap,
                    ),
                ).setIntent(intent)
                .setLocusId(locusId)
                .setPerson(sender)
                .setLongLived(true)
                .setExtras(conversationShortcutAccountScopeExtras(snapshot.accountScope))
        if (directShareEligible) {
            builder.setCategories(setOf(CONVERSATION_SHARE_TARGET_CATEGORY))
        }
        return builder.build()
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

    private fun reactionNotificationAction(
        actionTarget: NotificationActionTarget,
        reaction: String,
    ): NotificationCompat.Action =
        NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_whitenoise,
                reaction,
                actionPendingIntent(actionTarget, NotificationActionKind.REACT, reaction),
            ).setShowsUserInterface(false)
            .build()

    private fun actionPendingIntent(
        actionTarget: NotificationActionTarget,
        kind: NotificationActionKind,
        reaction: String? = null,
    ): PendingIntent {
        val actionIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                NotificationActions.applyToIntent(this, kind, actionTarget, reaction)
            }
        val mutableFlag =
            if (kind == NotificationActionKind.REPLY) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
        return PendingIntent.getBroadcast(
            context,
            NotificationActions.requestCode(kind, actionTarget.notificationTag, reaction),
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
    val shortcutId: String?,
    val conversationAvatarUrl: String?,
    val conversationAvatarBitmap: Bitmap?,
    val senderAvatarUrl: String?,
    val senderAvatarBitmap: Bitmap?,
) {
    val requiresRemoteAvatarResolution: Boolean
        get() =
            (!conversationAvatarUrl.isNullOrBlank() && conversationAvatarBitmap == null) ||
                (!senderAvatarUrl.isNullOrBlank() && senderAvatarBitmap == null)
}

private data class ConversationShortcutSnapshot(
    val shortcutId: String,
    val accountScope: String,
    val shortLabel: String,
    val longLabel: String,
    val notificationTag: String,
    val senderName: String,
    val senderKey: String,
    val avatarUrl: String?,
    val avatarApplied: Boolean,
    val senderAvatarUrl: String?,
    val senderAvatarApplied: Boolean,
    val directShareEligible: Boolean,
)

/**
 * Normal bitmaps avoid IconCompat's adaptive-icon safe-zone crop, which can cut
 * into already-tight headshots. Android's conversation surfaces still apply
 * their own circular presentation. Missing avatars get a stable per-conversation
 * monogram instead of every shortcut sharing the launcher icon.
 */
internal fun notificationConversationIcon(
    title: String,
    seed: String,
    avatarBitmap: Bitmap?,
): IconCompat = IconCompat.createWithBitmap(avatarBitmap ?: notificationMonogramBitmap(title, seed))

internal fun notificationMonogramBitmap(
    title: String,
    seed: String,
    sizePx: Int = NOTIFICATION_MONOGRAM_SIZE_PX,
): Bitmap {
    require(sizePx > 0) { "sizePx must be positive" }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(notificationMonogramBackgroundColor(seed))
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    val baseline = sizePx / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(notificationAvatarInitials(title), sizePx / 2f, baseline, paint)
    return bitmap
}

internal fun notificationMonogramBackgroundColor(seed: String): Int {
    val hue = Math.floorMod(seed.hashCode(), 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.45f))
}

internal fun notificationAvatarInitials(title: String): String {
    val words = title.trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotBlank)
    val initials =
        buildString {
            words.take(if (words.size > 1) 2 else 1).forEach { word ->
                word.firstMonogramCodePoint()?.let(::appendCodePoint)
            }
        }.uppercase(Locale.ROOT)
    return initials.ifBlank { "?" }
}

private fun String.firstMonogramCodePoint(): Int? {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        offset += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        if (!Character.isWhitespace(codePoint) &&
            !Character.isISOControl(codePoint) &&
            type != Character.FORMAT.toInt()
        ) {
            return codePoint
        }
    }
    return null
}

internal fun notificationSenderPerson(
    content: LocalNotificationContent,
    avatarBitmap: Bitmap?,
): Person =
    Person
        .Builder()
        .setName(content.senderName)
        .setKey(content.senderKey)
        .apply {
            avatarBitmap?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()

private const val NOTIFICATION_MONOGRAM_SIZE_PX = 128
private const val AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS = 2_500L
private const val EXTRA_CONTENT_REDACTED = "dev.ipf.whitenoise.android.notify.content_redacted"

private val notificationEnrichmentScope =
    CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("notification-card-enrichment"),
    )

private suspend fun resolveNotificationAvatarBitmap(url: String?): Bitmap? {
    val normalizedUrl = url?.takeUnless(String::isBlank)
    return normalizedUrl?.let { avatarUrl ->
        AvatarImageLoader.peekBitmap(avatarUrl)
            ?: withTimeoutOrNull(AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS) {
                // Enrichment is detached from the first alert. This bound now
                // limits only the optional rich-card update.
                AvatarImageLoader.loadBitmap(avatarUrl)
            }
    }
}

private inline fun notificationDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMLocalNotify", message())
}
