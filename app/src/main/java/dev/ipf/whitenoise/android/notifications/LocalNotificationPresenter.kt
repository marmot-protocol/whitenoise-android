package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.dismissalTime
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

private const val CONVERSATION_DISMISSAL_RETRY_DELAY_MS = 25L

@SuppressLint("MissingPermission")
private fun postLocalNotification(
    manager: NotificationManagerCompat,
    tag: String,
    id: Int,
    notification: Notification,
) {
    manager.notify(tag, id, notification)
}

class LocalNotificationPresenter(
    private val context: Context,
    private val shortcutPublisher: (ShortcutInfoCompat) -> Unit = { shortcut ->
        check(ShortcutManagerCompat.pushDynamicShortcut(context, shortcut))
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
    private val quickReactionChoices: () -> List<String> = { notificationQuickReactionChoices(context) },
    private val notificationCanceller: (NotificationManagerCompat, String, Int) -> Unit = { manager, tag, id ->
        manager.cancel(tag, id)
    },
    private val postPacer: NotificationPostPacer = NotificationPostPacer.shared,
    private val alertBudget: NotificationAlertBudget = NotificationAlertBudget(),
    private val spokenAudioPlaying: () -> Boolean = { quietAlertForSpokenAudio(context) },
    private val dismissalRetryDelay: suspend () -> Unit = { delay(CONVERSATION_DISMISSAL_RETRY_DELAY_MS) },
    private val groupReconciliation: () -> Unit = { NotificationGroupReconciler.shared(context).request() },
    private val emojiArtworkPreparer: suspend (String, String?) -> NotificationEmojiArtifact? = { text, source ->
        notificationEmojiArtwork(context, text, source)
    },
    private val emojiArtworkTimeoutMs: Long = NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS,
    // First argument is opaque account scope, never the local account label stored in a system payload.
    private val contactAvatarOverride: (String, String) -> Pair<Boolean, Bitmap?> = { _, _ -> false to null },
    // The platform's current dynamic shortcut ids, read before trusting a cached publication snapshot.
    private val dynamicShortcutIds: () -> Set<String> = {
        ShortcutManagerCompat.getDynamicShortcuts(context).mapTo(mutableSetOf()) { it.id }
    },
    // Kept last so callers may still pass it as a trailing lambda.
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification> = { manager ->
        manager.activeNotifications
    },
) {
    /** The catch-up cohort boundary first posts are judged against; the catch-up coordinator drives it. */
    internal val catchUpWindow: NotificationCatchUpWindow
        get() = alertBudget.catchUpWindow

    private val enrichmentRunner = NotificationEnrichmentRunner(enrichmentLauncher)

    private val tapTokens = NotificationTapTokens.create(context)
    private val interactions = NotificationInteractionFactory(context, tapTokens)
    private val cardContent = NotificationCardContent(context, contactAvatarOverride)
    private val conversationShortcuts =
        NotificationConversationShortcuts(
            context = context,
            shortcutPublisher = shortcutPublisher,
            dynamicShortcutIds = dynamicShortcutIds,
            activeNotificationsProvider = activeNotificationsProvider,
            currentContactAvatar = ::currentContactAvatar,
            interactions = interactions,
        )
    private val conversationVibrationPreferences = ConversationVibrationPreferences(context)
    private val profileNotificationOverrides by lazy { ProfileNotificationOverridePreferences(context) }
    private val profileNotificationChannels by lazy { ProfileNotificationChannels(context) }

    // First used from show()'s Default-dispatcher routing block, so defer the
    // SharedPreferences-backed construction instead of adding disk-backed work
    // to AppState's main-thread initialization.
    private val conversationNotificationRouting by lazy { ConversationNotificationRouting(context) }
    private val publisher =
        NotificationCardPublisher(
            context,
            cardContent,
            conversationShortcuts,
            notificationPoster,
            notificationCanceller,
            activeNotificationsProvider,
            groupReconciliation,
        )
    private val retirement =
        ConversationNotificationRetirement(
            context,
            publisher,
            tapTokens,
            nowMillis,
            dismissalRetryDelay,
            activeNotificationsProvider,
        )
    private val reconciler = ConversationNotificationContentReconciler(context, publisher, retirement)
    private val replyCompletion = NotificationReplyCompletion(context, publisher)

    fun ensureChannels() {
        NotificationChannels.ensureChannels(context)
        reconcileNotificationGroup()
    }

    /** Rechecks OS cards on process start/resume and after auto-cancel notification taps. */
    fun reconcileNotificationGroup() = groupReconciliation()

    fun hideConversationShortcutsFromDirectShare() = conversationShortcuts.hideConversationShortcutsFromDirectShare()

    /** Clears shortcut presentation under the caller's acknowledged credential-revocation fence. */
    fun clearRevokedConversationShortcutsForAccount(
        accountRef: String,
        includeUnscopedLegacy: Boolean,
    ) = conversationShortcuts.clearRevokedConversationShortcutsForAccount(accountRef, includeUnscopedLegacy)

    /** Called off the main thread in the same publication-lock section as platform removal. */
    internal fun forgetConversationShortcut(
        accountRef: String,
        groupIdHex: String,
    ) = conversationShortcuts.forgetConversationShortcut(accountRef, groupIdHex)

    fun canPostNotifications(): Boolean = notificationPermissionGranted(context)

    /**
     * Silently reconciles active sender lines for one account-scoped contact nickname.
     * Cards are re-read under their normal write lock so dismissed or replaced cards are never resurrected.
     */
    suspend fun refreshContactSenderName(
        accountRef: String,
        senderAccountIdHex: String,
        senderName: String,
        isRefreshCurrent: () -> Boolean = { true },
    ): Int =
        withContext(Dispatchers.Default) {
            if (accountRef.isBlank() || senderAccountIdHex.isBlank() || senderName.isBlank()) return@withContext 0
            if (profileNotificationOverrides.hasChoice(accountRef, senderAccountIdHex)) {
                profileNotificationChannels.refreshExisting(
                    accountRef,
                    senderAccountIdHex,
                    senderName,
                    isRefreshCurrent,
                )
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@withContext 0
            val active =
                try {
                    activeNotificationsProvider(manager)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return@withContext 0
                }
            var refreshed = 0
            active
                .filter { LocalNotificationFormatter.deterministicTagBelongsToAccount(it.tag, accountRef) }
                .forEach { snapshot ->
                    ConversationCardPostSynchronizer.withLock(
                        snapshot.tag.orEmpty(),
                        snapshot.id,
                        ConversationCardOp.REFRESH_CONTACT_NAME,
                    ) {
                        if (!isRefreshCurrent()) return@withLock
                        val live = publisher.activeNotification(manager, snapshot.tag, snapshot.id) ?: return@withLock
                        val renamed =
                            cardContent.renamedSenderNotification(
                                live.notification,
                                senderAccountIdHex,
                                senderName,
                            ) ?: return@withLock
                        if (
                            publisher.postNotificationSafely(
                                NotificationManagerCompat.from(context),
                                live.tag.orEmpty(),
                                live.id,
                                NotificationCompat
                                    .Builder(context, renamed)
                                    .addExtras(
                                        contactPictureAccountExtras(accountRef),
                                    ).build(),
                                recordedAtMs = dismissalTime(live),
                                mustBeLive = true,
                            ) == NotificationCardWriteResult.WRITTEN
                        ) {
                            refreshed += 1
                        }
                    }
                }
            refreshed
        }

    /** Invalidates in-flight cards and captures the exact opening-time cleanup boundary. */
    internal fun captureConversationDismissal(
        accountRef: String,
        groupIdHex: String,
    ): ConversationDismissalRequest? = retirement.captureConversationDismissal(accountRef, groupIdHex)

    /** Captures and clears every opening-time card for one conversation. */
    suspend fun dismissConversationMessages(
        accountRef: String,
        groupIdHex: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        shouldContinue: () -> Boolean = { true },
    ): Boolean = retirement.dismissConversationMessages(accountRef, groupIdHex, dispatcher, shouldContinue)

    /** Clears one captured opening boundary while its navigation owner remains current. */
    internal suspend fun dismissConversationMessages(
        request: ConversationDismissalRequest,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        shouldContinue: () -> Boolean = { true },
    ): Boolean = retirement.dismissConversationMessages(request, dispatcher, shouldContinue)

    /**
     * Runs one synchronous cancellation pass for callers that already own an
     * off-main lane. UI route cleanup uses [dismissConversationMessages] so it
     * can retry on its dedicated dispatcher.
     */
    fun dismissConversationMessagesImmediately(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = retirement.dismissConversationMessagesImmediately(accountRef, groupIdHex)

    /**
     * Silently reconciles every deterministic card of one conversation with the messages that may still be shown
     * (#3323, #2065). Each retained entry carries its own message id and the time it was shown; an entry for which
     * [isEntryValid] fails is dropped. A card left with nothing is cancelled, and a card that lost only some entries
     * is rewritten from the live card with just the valid ones, silently and under its card lock, so a newer valid
     * sibling survives without a second alert. A reaction card quotes a target the card does not identify, so it is
     * cancelled when [cancelReactionCards] is set. Membership cards carry no message content and are left alone.
     * Returns whether every card was confirmed reconciled.
     */
    fun reconcileConversationContent(
        accountRef: String,
        groupIdHex: String,
        cancelReactionCards: Boolean,
        isEntryValid: (entryMessageIdHex: String?, shownAtMs: Long) -> Boolean,
    ): Boolean = reconciler.reconcileConversationContent(accountRef, groupIdHex, cancelReactionCards, isEntryValid)

    /** Lists the conversation cards this app currently shows, for bounded restart reconciliation. */
    internal fun activeConversationCards(): List<ActiveConversationCard> = reconciler.activeConversationCards()

    /** Cancels [update]'s card only while it still shows that same newest message, keeping newer siblings. */
    internal fun cancelNotificationUpdateCardIfCurrent(update: NotificationUpdateFfi) =
        retirement.cancelNotificationUpdateCardIfCurrent(
            update = update,
        )

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
    ): Boolean =
        retirement.dismissActionNotificationAndOlderSiblings(
            notificationTag,
            notificationId,
            actedMessageIdHex,
            accountRef,
            groupIdHex,
            sinceMs,
        )

    // Clear reaction, mention, and invite sibling cards, but keep any that were
    // (re)posted after [sinceMs] — a reaction, mention, or invite that landed
    // during the action window is genuinely new and must survive.
    fun dismissConversationSiblingCardsNotNewerThan(
        accountRef: String,
        groupIdHex: String,
        sinceMs: Long,
    ): Boolean = retirement.dismissConversationSiblingCardsNotNewerThan(accountRef, groupIdHex, sinceMs)

    /** Retains one dismissal lifetime across a caller's quote-only retry without replacing inner show ownership. */
    internal suspend fun withShowLifetime(
        update: NotificationUpdateFfi,
        post: suspend (isNotDismissed: () -> Boolean) -> Boolean,
    ): Boolean {
        val key = LocalNotificationFormatter.notificationDismissalKey(update)
        return ConversationCardPostSynchronizer.withRegisteredShow(
            key.tag,
            key.id,
            ConversationCardScope(update.accountRef, update.groupIdHex),
        ) { token -> post { ConversationCardPostSynchronizer.isShowNotDismissed(token) } }
    }

    /**
     * Formats and publishes one notification under the caller's live eligibility gate.
     * Matching-message replacements also require newest-show ownership, and every
     * path rechecks both gates at the final serialized write boundary. A supplied
     * avatar bitmap is authoritative for this write; the matching URL is metadata
     * only and never causes a second fetch when its bitmap is present. The optional
     * write observer runs after the first successful platform notification write.
     * A quiet write settles its alert flags at that final write, against the live
     * same-key card, so it never ends a heads-up banner that card is still showing.
     * [identityContact] is the chat row's peer for a direct chat MDK reports as a
     * group (#3635). The card and its shortcut record them, and each write applies
     * their private picture as chosen at that moment, as for any direct chat.
     */
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
        conversationAvatarBitmap: Bitmap? = null,
        senderAvatarUrl: String? = null,
        senderAvatarBitmap: Bitmap? = null,
        identityContact: String? = null,
        silentUpdate: Boolean = false,
        replaceCurrentMessage: Boolean = false,
        onNotificationWritten: (() -> Unit)? = null,
        isPostStillAllowed: () -> Boolean = { true },
        shortNpub: (String) -> String,
    ): Boolean {
        val profileAllowsPost = { isPostStillAllowed() && !profileNotificationOverrides.isMuted(update) }
        if (!profileAllowsPost()) return false
        val previewToken = NotificationPreviewPreferences.capture(context)
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
        // A first post rings only if its catch-up cohort has not rung yet, no alert rang moments ago
        // (#1579) and, for a message that does not mention the reader, no other app is playing speech
        // (#1717). The ring is held from here, so a concurrent first post for another conversation stays
        // silent, and handed back below if this card is never written. Enrichment rewrites arrive with
        // silentUpdate already set and never touch the budget.
        val alertReservation =
            if (silentUpdate) {
                null
            } else {
                alertBudget.reserve(
                    nowMs = nowMillis(),
                    isMention = update.isMention,
                    accountRef = update.accountRef,
                    spokenAudioPlaying = {
                        update.trigger == NotificationTriggerFfi.NEW_MESSAGE && spokenAudioPlaying()
                    },
                )
            }
        val heldAlert = alertReservation?.takeIf { it.decision == NotificationAlertDecision.Alert }
        val silentPost = silentUpdate || alertReservation?.decision?.silent == true
        if (alertReservation != null && alertReservation.decision.silent) {
            notificationDebug {
                "silent first post key=${update.notificationKey.take(16)} reason=${alertReservation.decision}"
            }
        }
        val writeObserver: (() -> Unit)? =
            if (heldAlert != null) {
                {
                    heldAlert.commit()
                    onNotificationWritten?.invoke()
                }
            } else {
                onNotificationWritten
            }
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
        var written = false
        var rewriteLease: NotificationCardGeneration? = null
        var emojiArtifact: NotificationEmojiArtifact? = null
        var carriedArtworkLease: AutoCloseable? = null
        var emojiPreparationAttempted = false
        try {
            written =
                ConversationCardPostSynchronizer.withRegisteredShow(
                    notificationContent.notificationTag,
                    notificationContent.notificationId,
                    ConversationCardScope(update.accountRef, update.groupIdHex),
                ) { showToken ->
                    // Capture dismissal ownership before optional image I/O can suspend.
                    if (!redactContent && decision.style == NotificationStyleChoice.Messaging) {
                        emojiPreparationAttempted = true
                        emojiArtifact =
                            prepareNotificationEmojiArtwork(
                                emojiArtworkPreparer,
                                notificationContent.body,
                                update.previewText,
                                emojiArtworkTimeoutMs,
                            )
                    }
                    val showGenerationAllowsPost = {
                        if (replaceCurrentMessage) {
                            ConversationCardPostSynchronizer.isShowCurrent(showToken)
                        } else {
                            ConversationCardPostSynchronizer.isShowNotDismissed(showToken)
                        }
                    }
                    ConversationCardPostSynchronizer.awaitTestBarrier(
                        ConversationCardOp.SHOW_NOTIFY,
                        ConversationCardBarrier.AFTER_REGISTER,
                        notificationContent.notificationTag,
                        notificationContent.notificationId,
                    )
                    if (!profileAllowsPost() || !showGenerationAllowsPost()) return@withRegisteredShow false
                    var replacementHasChanged = false
                    var existingCardChannel: String? = null
                    val rewriteGeneration =
                        if (silentUpdate || replaceCurrentMessage) {
                            withContext(Dispatchers.Default) {
                                synchronized(UserEventNotificationGroup.mutationLock) {
                                    val platform = context.getSystemService(NotificationManager::class.java)
                                    val active =
                                        platform?.let {
                                            publisher.activeNotification(
                                                it,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                        }
                                    val messageId =
                                        active?.notification?.extras?.getString(
                                            LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
                                        )
                                    val matchingCard =
                                        active?.takeIf { card ->
                                            if (update.trigger == NotificationTriggerFfi.GROUP_INVITE) {
                                                card.matchesInvite(update.accountRef, update.groupIdHex)
                                            } else {
                                                messageId == update.messageIdHex
                                            }
                                        }
                                    existingCardChannel = matchingCard?.notification?.channelId
                                    replacementHasChanged =
                                        replaceCurrentMessage &&
                                        active != null &&
                                        messageId != update.messageIdHex
                                    active
                                        ?.takeIf { messageId == update.messageIdHex }
                                        ?.notification
                                        ?.extras
                                        ?.getString(EXTRA_GENERATION)
                                        ?.takeIf(String::isNotBlank)
                                        ?.also { rewriteLease = NotificationCardGenerations.register(it) }
                                }
                            }
                        } else {
                            null
                        }
                    if (replacementHasChanged) return@withRegisteredShow false
                    val category = NotificationChannelSpec.forUpdate(update)
                    // Re-read category policy at the final platform write as well as before channel selection.
                    // A custom person channel must not outlive category/group disablement while pacing waits.
                    val usesActorCategory =
                        update.trigger != NotificationTriggerFfi.NEW_MESSAGE &&
                            profileNotificationSoundAuthor(update) != null
                    val categoryAllowsPost = {
                        if (!usesActorCategory) {
                            true
                        } else {
                            // settings() only reads existing routing; it never materializes a channel.
                            val categorySettings =
                                conversationShortcutId(update.accountRef, update.groupIdHex)
                                    ?.let { shortcutId ->
                                        conversationNotificationRouting
                                            .settings(
                                                NotificationConversationDescriptor(
                                                    shortcutId,
                                                    update.isDm,
                                                    conversationTitleOverride,
                                                    ConversationVibrationPattern.SYSTEM_DEFAULT,
                                                ),
                                            ).filter { it.channel == category }
                                    }.orEmpty() +
                                    ConversationNotificationCategorySetting(
                                        category,
                                        ConversationNotificationScope.USE_GLOBAL_DEFAULT,
                                        false,
                                        AndroidNotificationSettingsTarget.Global(category),
                                    )
                            category !in androidBlockedConversationCategories(context, categorySettings)
                        }
                    }
                    if (usesActorCategory && !withContext(Dispatchers.IO) { categoryAllowsPost() }) {
                        return@withRegisteredShow false
                    }
                    // Ordinary messages keep their required People/conversation child.
                    // Other event types inherit the stable global channel until this
                    // chat has an explicit or legacy custom override.
                    val channelShortcutId =
                        if (!redactContent) {
                            conversationShortcutId(update.accountRef, update.groupIdHex)
                        } else {
                            null
                        }
                    val messagingShortcutId =
                        channelShortcutId.takeIf { decision.style == NotificationStyleChoice.Messaging }
                    val hiddenShortcut: (() -> ShortcutInfoCompat)? =
                        messagingShortcutId?.let { id ->
                            { prepareGenericNotificationShortcut(context, id, update.accountRef, update.groupIdHex) }
                        }
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
                            existingCardChannel ?: profileNotificationSoundAuthor(update)?.let { author ->
                                val selection = profileNotificationOverrides.get(update.accountRef, author)
                                if (selection.mode == ProfileNotificationMode.CUSTOM) {
                                    profileNotificationChannels.existing(
                                        update.accountRef,
                                        author,
                                        selection.vibration,
                                        senderNameOverride ?: update.sender.displayName,
                                        redactContent,
                                    )
                                } else {
                                    null
                                }
                            } ?: conversationNotificationRouting
                                .resolveForPost(
                                    channel = NotificationChannelSpec.forUpdate(update),
                                    conversationShortcutId = channelShortcutId,
                                    conversationTitle = conversationTitleOverride,
                                    primaryVibrationPattern = vibrationPattern,
                                ).channelId
                        }
                    val quietChannel =
                        runCatching {
                            context
                                .getSystemService(NotificationManager::class.java)
                                ?.getNotificationChannel(channelId)
                                ?.importance
                        }.getOrNull()?.let { importance ->
                            importance in NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_LOW
                        } == true
                    val silentChild = silentPost || quietChannel || decision.importance == ChannelImportance.LOW
                    // #1717 evidence: the OS channel, never the app, plays this alert. Debug builds only.
                    notificationDebug {
                        messageAlertDiagnostic(
                            context,
                            NotificationChannelSpec.forUpdate(update),
                            channelId,
                            existingCardChannel,
                            silentChild,
                        ).line()
                    }
                    val builder =
                        NotificationCompat
                            .Builder(context, channelId)
                            .apply {
                                NotificationPreviewPreferences.stamp(
                                    this,
                                    previewToken,
                                    rewriteGeneration != null,
                                )
                                UserEventNotificationGroup.decorateChild(
                                    context,
                                    this,
                                    NotificationGroupChild(
                                        notificationContent.notificationTag,
                                        notificationContent.notificationId,
                                        rewriteGeneration ?: showToken.notificationGeneration.id,
                                    ),
                                    silent = silentChild,
                                )
                            }.setSmallIcon(R.drawable.ic_stat_whitenoise)
                            .setContentIntent(
                                interactions.conversationPendingIntent(update, notificationContent.notificationTag),
                            ).setCategory(decision.category)
                            .setPriority(decision.importance.toCompatPriority())
                            .setShowWhen(true)
                            .setAutoCancel(true)
                            // Quiet flags are settled at the final write, against the live same-key card.
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    // Name the recipient identity in the header when multi-account (#836).
                    if (!redactContent && !recipientAccountSubtext.isNullOrBlank()) {
                        builder.setSubText(recipientAccountSubtext)
                    }
                    if (
                        decision.style == NotificationStyleChoice.Plain ||
                        decision.style == NotificationStyleChoice.Messaging
                    ) {
                        stampConversationCardMessageId(builder, update.messageIdHex)
                    }

                    builder.addExtras(contactPictureAccountExtras(update.accountRef))
                    var messagingPost: MessagingPostContext? = null
                    var reactorIdentity: NotificationIdentityImage? = null
                    val reactionGroupTitle = notificationContent.conversationTitle.takeIf { identityContact == null }
                    when (val style = decision.style) {
                        // Reactions get their own self-contained card (own tag/id on the
                        // reactions channel, see LocalNotificationFormatter) so they're muted
                        // independently of messages. Android can use the message template for that separate card.
                        NotificationStyleChoice.Plain -> {
                            builder.setContentTitle(notificationContent.title)
                            if (notificationContent.body.isNotBlank()) {
                                builder
                                    .setContentText(notificationContent.body)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.body))
                            } else {
                                // Expand the same sentence for long group names without repeating its title.
                                builder.setStyle(
                                    NotificationCompat
                                        .BigTextStyle()
                                        .setBigContentTitle("")
                                        .bigText(notificationContent.title),
                                )
                            }
                            // #3635: a reaction card shows who reacted. A missing picture arrives through the late
                            // identity refresh, and redacted or hidden-preview cards get none.
                            if (notificationContent.isReactionPreview && !redactContent && previewToken.allowed) {
                                val reactor =
                                    notificationContent.reactorImage(
                                        senderAvatarBitmap ?: cachedAvatarBitmap(senderAvatarUrl),
                                    )
                                reactorIdentity = reactor
                                builder.setConversationIdentityImage(reactor)
                            }
                        }

                        // Messages stack into one per-conversation card; invites are
                        // one-off events, so keep them as a plain expandable notification.
                        NotificationStyleChoice.Messaging -> {
                            val (resolvedConversationAvatarBitmap, resolvedSenderAvatarBitmap) =
                                if (redactContent) {
                                    null to null
                                } else {
                                    (conversationAvatarBitmap ?: cachedAvatarBitmap(conversationAvatarUrl)) to
                                        (senderAvatarBitmap ?: cachedAvatarBitmap(senderAvatarUrl))
                                }
                            // #3635: a group row shows its member's picture, or their monogram until it arrives.
                            val sender =
                                notificationSenderPerson(
                                    notificationContent,
                                    resolvedSenderAvatarBitmap
                                        ?: if (redactContent) {
                                            null
                                        } else {
                                            notificationMonogramBitmap(
                                                notificationContent.senderName,
                                                notificationContent.senderKey,
                                            )
                                        },
                                    redacted = redactContent || !previewToken.allowed,
                                )
                            // #3311: every rich card carries its conversation identity picture in the first
                            // payload, for a direct chat as well as a group, so the native conversation picture
                            // never depends on a later shortcut enrichment.
                            // Redacted cards get none.
                            if (!redactContent) {
                                builder.setConversationIdentityImage(
                                    notificationContent.identityImage(
                                        seed = messagingShortcutId ?: notificationContent.notificationTag,
                                        titleOverride = conversationTitleOverride,
                                        conversationAvatar = resolvedConversationAvatarBitmap,
                                        senderAvatar = resolvedSenderAvatarBitmap,
                                        contact = identityContact,
                                    ),
                                )
                            }
                            if (!redactContent && messagingShortcutId != null) {
                                val locusId = LocusIdCompat(messagingShortcutId)
                                builder
                                    .setShortcutId(messagingShortcutId)
                                    .setLocusId(locusId)
                                    .addPerson(sender)
                            }
                            if (redactContent) {
                                builder.addExtras(Bundle().apply { putBoolean(EXTRA_CONTENT_REDACTED, true) })
                            }
                            if (!redactContent) {
                                val quickReactions =
                                    if (decision.actions.contains(NotificationActionKind.REPLY)) {
                                        withContext(Dispatchers.Default) {
                                            notificationQuickReactionChoices(quickReactionChoices())
                                        }
                                    } else {
                                        emptyList()
                                    }
                                NotificationActions
                                    .targetFromUpdate(update, notificationContent.notificationTag, notificationContent.notificationId)
                                    ?.let { actionTarget ->
                                        decision.actions.forEach { action ->
                                            when (action) {
                                                NotificationActionKind.REPLY ->
                                                    builder.addAction(
                                                        interactions.replyNotificationAction(
                                                            actionTarget,
                                                            quickReactions,
                                                        ),
                                                    )
                                                // Reaction choices ride on Reply's RemoteInput so SystemUI
                                                // can render them inline.
                                                NotificationActionKind.REACT -> Unit
                                                NotificationActionKind.MARK_READ ->
                                                    builder.addAction(
                                                        interactions.markReadNotificationAction(actionTarget),
                                                    )
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
                                    conversationAvatarBitmap = resolvedConversationAvatarBitmap,
                                    senderAvatarUrl = senderAvatarUrl,
                                    senderAvatarBitmap = resolvedSenderAvatarBitmap,
                                    identityContact = identityContact,
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
                    // #3311: Android classifies a card as a conversation only when its shortcut already exists, so the
                    // identity shortcut is published from the pictures already in hand, with no network wait, before
                    // the one first post. Redacted and hidden-preview posts keep only the generic shortcut.
                    val heldShortcutId =
                        messagingPost?.takeIf { !redactContent && previewToken.allowed }?.let { messaging ->
                            publishShortcutBeforePost(update, notificationContent, messaging, directShareEligible) {
                                profileAllowsPost() && showGenerationAllowsPost()
                            }
                        } ?: reactorIdentity?.let {
                            publishReactionShortcutBeforePost(
                                update,
                                notificationContent,
                                reactionGroupTitle,
                                conversationAvatarBitmap.takeIf { reactionGroupTitle != null },
                            ) {
                                profileAllowsPost() && showGenerationAllowsPost()
                            }?.also(builder::setShortcutId)
                        }
                    val reactionInlineArtwork =
                        reactorIdentity != null &&
                            heldShortcutId == null &&
                            applyReactionNotificationArtwork(
                                context,
                                builder,
                                notificationContent,
                                reservesPicture = true,
                            )

                    // Builds under the card lock, so the quiet flags see the card the platform will update.
                    val buildSettled = { afterOwnCancel: Boolean ->
                        builder
                            .settledForWrite(
                                heldAlert,
                                silentPost,
                                replaceCurrentMessage,
                                notificationContent.notificationTag,
                                notificationContent.notificationId,
                                liveCardIsBeingCancelled = afterOwnCancel,
                            ).build()
                    }
                    // Reserve a write slot before the serialized write so a burst of updates for one busy
                    // conversation never exceeds the platform's per-app notification rate limit.
                    val posted =
                        try {
                            postPacer.awaitSlot()
                            val notificationManager = NotificationManagerCompat.from(context)
                            withContext(Dispatchers.Default) {
                                val messaging = messagingPost
                                if (messaging != null) {
                                    ConversationCardPostSynchronizer.withLock(
                                        notificationContent.notificationTag,
                                        notificationContent.notificationId,
                                        ConversationCardOp.SHOW_NOTIFY,
                                    ) {
                                        if (
                                            !profileAllowsPost() ||
                                            !showGenerationAllowsPost()
                                        ) {
                                            return@withLock false
                                        }
                                        val carried =
                                            if (redactContent) {
                                                null
                                            } else {
                                                synchronized(UserEventNotificationGroup.mutationLock) {
                                                    publisher
                                                        .existingConversationMessages(
                                                            notificationContent.notificationTag,
                                                            notificationContent.notificationId,
                                                            replacingMessageIdHex =
                                                                update.messageIdHex
                                                                    ?.takeIf(String::isNotBlank)
                                                                    ?.takeIf { replaceCurrentMessage },
                                                        )?.also { history ->
                                                            carriedArtworkLease =
                                                                retainNotificationEmojiHistoryArtwork(context, history)
                                                        }
                                                }
                                            }
                                        ConversationCardPostSynchronizer.awaitTestBarrier(
                                            ConversationCardOp.SHOW_NOTIFY,
                                            ConversationCardBarrier.AFTER_READ,
                                            notificationContent.notificationTag,
                                            notificationContent.notificationId,
                                        )
                                        val presentationTimestampMs = nowMillis()
                                        stampPresentationTime(builder, decision.channelId, decision.category, presentationTimestampMs)
                                        // Keep the native conversation template for every message length. Switching
                                        // a long first message to expandable text moves the identity to a trailing
                                        // picture and loses Android's conversation presentation (#3505).
                                        builder.setStyle(
                                            cardContent.messagingStyle(
                                                notificationContent,
                                                messaging.conversationTitleOverride,
                                                decision.historyCap,
                                                carried,
                                                messaging.sender,
                                                presentationTimestampMs,
                                                emojiArtifact?.uri,
                                                update.messageIdHex,
                                            ),
                                        )
                                        val notification = buildSettled(false)
                                        ConversationCardPostSynchronizer.awaitTestBarrier(
                                            ConversationCardOp.SHOW_NOTIFY,
                                            ConversationCardBarrier.BEFORE_WRITE,
                                            notificationContent.notificationTag,
                                            notificationContent.notificationId,
                                        )
                                        if (
                                            !profileAllowsPost() ||
                                            !showGenerationAllowsPost()
                                        ) {
                                            return@withLock false
                                        }
                                        val firstPostResult =
                                            publisher.postNotificationSafely(
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                                notification,
                                                writeObserver,
                                                mustBeLive = rewriteGeneration != null,
                                                hiddenShortcut = hiddenShortcut,
                                                finalPostAllowed = {
                                                    categoryAllowsPost() &&
                                                        profileAllowsPost() &&
                                                        showGenerationAllowsPost() &&
                                                        rewriteLease?.dismissed?.get() != true
                                                },
                                            )
                                        if (firstPostResult == NotificationCardWriteResult.WRITTEN) {
                                            ConversationCardPostSynchronizer.awaitTestBarrier(
                                                ConversationCardOp.SHOW_NOTIFY,
                                                ConversationCardBarrier.AFTER_WRITE,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                            publisher.retainPostedCardUnlessDismissed(
                                                showToken,
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                        } else if (firstPostResult == NotificationCardWriteResult.FAILED) {
                                            publisher.cancelNotification(
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                            if (carried.isNullOrEmpty() && emojiArtifact == null) {
                                                false
                                            } else {
                                                if (!profileAllowsPost() || !showGenerationAllowsPost()) {
                                                    return@withLock false
                                                }
                                                builder.setStyle(
                                                    cardContent.messagingStyle(
                                                        notificationContent,
                                                        messaging.conversationTitleOverride,
                                                        decision.historyCap,
                                                        carriedHistory = null,
                                                        sender = messaging.sender,
                                                        newMessageTimestampMs = presentationTimestampMs,
                                                        emojiArtwork = null,
                                                        messageIdHex = update.messageIdHex,
                                                    ),
                                                )
                                                val cleanNotification = buildSettled(true)
                                                val retryResult =
                                                    publisher.postNotificationSafely(
                                                        notificationManager,
                                                        notificationContent.notificationTag,
                                                        notificationContent.notificationId,
                                                        cleanNotification,
                                                        writeObserver,
                                                        mustBeLive = rewriteGeneration != null,
                                                        hiddenShortcut = hiddenShortcut,
                                                        finalPostAllowed = {
                                                            categoryAllowsPost() &&
                                                                profileAllowsPost() &&
                                                                showGenerationAllowsPost() &&
                                                                rewriteLease?.dismissed?.get() != true
                                                        },
                                                    )
                                                if (retryResult == NotificationCardWriteResult.WRITTEN) {
                                                    ConversationCardPostSynchronizer.awaitTestBarrier(
                                                        ConversationCardOp.SHOW_NOTIFY,
                                                        ConversationCardBarrier.AFTER_WRITE,
                                                        notificationContent.notificationTag,
                                                        notificationContent.notificationId,
                                                    )
                                                    publisher.retainPostedCardUnlessDismissed(
                                                        showToken,
                                                        notificationManager,
                                                        notificationContent.notificationTag,
                                                        notificationContent.notificationId,
                                                    )
                                                } else {
                                                    if (retryResult ==
                                                        NotificationCardWriteResult.FAILED
                                                    ) {
                                                        publisher.cancelNotification(
                                                            notificationManager,
                                                            notificationContent.notificationTag,
                                                            notificationContent.notificationId,
                                                        )
                                                    }
                                                    false
                                                }
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                } else {
                                    ConversationCardPostSynchronizer.withLock(
                                        notificationContent.notificationTag,
                                        notificationContent.notificationId,
                                        ConversationCardOp.SHOW_NOTIFY,
                                    ) {
                                        if (
                                            !profileAllowsPost() ||
                                            !showGenerationAllowsPost()
                                        ) {
                                            return@withLock false
                                        }
                                        val presentationTimestampMs = nowMillis()
                                        stampPresentationTime(builder, decision.channelId, decision.category, presentationTimestampMs)
                                        reactorIdentity?.takeIf { heldShortcutId != null }?.let { reactor ->
                                            builder
                                                .setLargeIcon(null as Bitmap?)
                                                .setStyle(
                                                    reactionStyle(
                                                        notificationContent,
                                                        reactor,
                                                        reactionGroupTitle,
                                                        presentationTimestampMs,
                                                    ),
                                                )
                                        }
                                        val notification = buildSettled(false)
                                        ConversationCardPostSynchronizer.awaitTestBarrier(
                                            ConversationCardOp.SHOW_NOTIFY,
                                            ConversationCardBarrier.BEFORE_WRITE,
                                            notificationContent.notificationTag,
                                            notificationContent.notificationId,
                                        )
                                        if (
                                            !profileAllowsPost() ||
                                            !showGenerationAllowsPost()
                                        ) {
                                            return@withLock false
                                        }
                                        var postResult =
                                            publisher.postNotificationSafely(
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                                notification,
                                                writeObserver,
                                                mustBeLive = rewriteGeneration != null,
                                                hiddenShortcut = hiddenShortcut,
                                                finalPostAllowed = {
                                                    categoryAllowsPost() &&
                                                        profileAllowsPost() &&
                                                        showGenerationAllowsPost() &&
                                                        rewriteLease?.dismissed?.get() != true
                                                },
                                            )
                                        if (postResult == NotificationCardWriteResult.FAILED && reactionInlineArtwork) {
                                            publisher.cancelNotification(
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                            if (!profileAllowsPost() || !showGenerationAllowsPost()) {
                                                return@withLock false
                                            }
                                            builder
                                                .setCustomContentView(null)
                                                .setCustomBigContentView(null)
                                                .setCustomHeadsUpContentView(null)
                                                .setStyle(
                                                    NotificationCompat.BigTextStyle().bigText(notificationContent.body),
                                                )
                                            postResult =
                                                publisher.postNotificationSafely(
                                                    notificationManager,
                                                    notificationContent.notificationTag,
                                                    notificationContent.notificationId,
                                                    buildSettled(true),
                                                    writeObserver,
                                                    // This attempt owns the cancellation;
                                                    // generation/dismissal gates still apply.
                                                    mustBeLive = false,
                                                    hiddenShortcut = hiddenShortcut,
                                                    finalPostAllowed = {
                                                        categoryAllowsPost() &&
                                                            profileAllowsPost() &&
                                                            showGenerationAllowsPost() &&
                                                            rewriteLease?.dismissed?.get() != true
                                                    },
                                                )
                                        }
                                        if (postResult == NotificationCardWriteResult.WRITTEN) {
                                            ConversationCardPostSynchronizer.awaitTestBarrier(
                                                ConversationCardOp.SHOW_NOTIFY,
                                                ConversationCardBarrier.AFTER_WRITE,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                            publisher.retainPostedCardUnlessDismissed(
                                                showToken,
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                        } else {
                                            if (postResult ==
                                                NotificationCardWriteResult.FAILED
                                            ) {
                                                publisher.cancelNotification(
                                                    notificationManager,
                                                    notificationContent.notificationTag,
                                                    notificationContent.notificationId,
                                                )
                                            }
                                            false
                                        }
                                    }
                                }
                            }
                        } finally {
                            // The card is written, abandoned, cancelled or failed, so a Direct Share refresh may now
                            // judge it as a live card, or drop a shortcut no card names.
                            heldShortcutId?.let(InFlightConversationShortcuts::release)
                        }
                    if (!posted) return@withRegisteredShow false
                    messagingPost?.takeUnless { redactContent }?.let { messaging ->
                        dispatchMessagingEnrichment(
                            update = update,
                            content = notificationContent,
                            messaging = messaging,
                            showToken = rewriteLease?.let { showToken.copy(notificationGeneration = it) } ?: showToken,
                            directShareEligible = directShareEligible,
                            isPostStillAllowed = profileAllowsPost,
                        )
                    }
                    notificationDebug {
                        // Never log the title/body — they carry sender / group names (PII).
                        "posted tag=${notificationContent.notificationTag.take(
                            16,
                        )} trigger=${update.trigger} group=${update.groupIdHex.take(8)}"
                    }
                    true
                }
        } finally {
            carriedArtworkLease?.close()
            emojiArtifact?.close()
            // Plain-text cards create no exports; their normal write observer already reconciles the summary.
            if (emojiPreparationAttempted && runCatching { hasNotificationEmojiArtwork(context) }.getOrDefault(false)) {
                groupReconciliation()
            }
            rewriteLease?.let(NotificationCardGenerations::release)
            if (!written) heldAlert?.release()
        }
        return written
    }

    /**
     * Publishes the conversation's identity shortcut before the first post (#3311), so even a brand-new
     * conversation's first card gets Android's conversation layout and picture without a second write. It reuses the
     * resolved pictures or the monogram and never waits on the network. An unchanged snapshot costs nothing, and a
     * failed publication is contained so the card still posts through the existing fallback. The published shortcut
     * is held in [InFlightConversationShortcuts] until the caller releases it after that write, so a Direct Share
     * refresh in between cannot delete it. Returns the held shortcut id, or null when nothing was published or held.
     */
    private suspend fun publishShortcutBeforePost(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        directShareEligible: Boolean,
        isPublishAllowed: () -> Boolean,
    ): String? {
        val shortcutId = messaging.shortcutId ?: return null
        return withContext(Dispatchers.Default) {
            conversationShortcuts.publishConversationShortcut(
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
                isPublishAllowed = isPublishAllowed,
                holdForPost = true,
                identityContact = messaging.identityContact,
            )
        }.takeIf { it }?.let { shortcutId }
    }

    /** Publishes the separate reaction shortcut before Android classifies the card for its template. */
    private suspend fun publishReactionShortcutBeforePost(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        groupTitle: String?,
        groupAvatar: Bitmap?,
        isPublishAllowed: () -> Boolean,
    ): String? {
        val id =
            reactionConversationShortcutId(update.accountRef, update.groupIdHex)
                ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R } ?: return null
        val published =
            withContext(Dispatchers.Default) {
                conversationShortcuts.publishReactionShortcut(
                    update,
                    content,
                    id,
                    groupTitle,
                    groupAvatar,
                    isPublishAllowed,
                )
            }
        return id.takeIf { published }
    }

    /** Hands optional picture work to its execution owner while keeping content and write checks here. */
    private suspend fun dispatchMessagingEnrichment(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        showToken: ConversationCardShowToken,
        directShareEligible: Boolean,
        isPostStillAllowed: () -> Boolean,
    ) {
        enrichmentRunner.runOrLaunch(
            requiresRemoteAvatarResolution = messaging.requiresRemoteAvatarResolution,
            showToken = showToken,
        ) {
            enrichMessagingNotification(
                update = update,
                content = content,
                messaging = messaging,
                showToken = showToken,
                directShareEligible = directShareEligible,
                isPostStillAllowed = isPostStillAllowed,
            )
        }
    }

    /**
     * Rewrites the card and republishes its shortcut only when a picture resolved after the post. An unchanged
     * picture leaves both alone, because the pre-post publish already published this shortcut and reported its use,
     * so a message reports the shortcut used once. A changed picture republishes it and reports its use once more.
     */
    private suspend fun enrichMessagingNotification(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        messaging: MessagingPostContext,
        showToken: ConversationCardShowToken,
        directShareEligible: Boolean,
        isPostStillAllowed: () -> Boolean,
    ) {
        val (conversationAvatarBitmap, senderAvatarBitmap) =
            resolveMessagingAvatars(messaging, notificationConversationDomain(update), notificationSenderDomain(update))
        val avatarChanged =
            conversationAvatarBitmap !== messaging.conversationAvatarBitmap ||
                senderAvatarBitmap !== messaging.senderAvatarBitmap
        val stillOwned = isPostStillAllowed() && ConversationCardPostSynchronizer.isShowCurrent(showToken)
        if (!avatarChanged || !stillOwned) return

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

    /**
     * Resolves the post's picture inputs, fetching only a slot that has a URL and no bitmap. The conversation slot
     * decodes in [conversationDomain], the sender slot in [senderDomain].
     */
    private suspend fun resolveMessagingAvatars(
        messaging: MessagingPostContext,
        conversationDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
        senderDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
    ): Pair<Bitmap?, Bitmap?> =
        resolveAvatars(
            NotificationIdentityPictureSources(
                conversationBitmap = messaging.conversationAvatarBitmap,
                conversationUrl = messaging.conversationAvatarUrl,
                senderBitmap = messaging.senderAvatarBitmap,
                senderUrl = messaging.senderAvatarUrl,
                conversationDomain = conversationDomain,
                senderDomain = senderDomain,
            ),
        )

    /**
     * Resolves both picture slots concurrently off the main thread, keeping a present bitmap and never fetching it.
     * The conversation slot first reads MDK's stored copy of the picture the chat list draws (#3635), so a picture
     * already on the device never depends on the network, and it replaces a picture the first post drew from
     * elsewhere, which stays when the store has none. A missing picture then comes from the slot's loader, or from
     * its raw URL only when the slot has no loader, as on the first post. Loaders run only in the late refresh, after
     * the enrichment permit is released, so they get a longer bound than a URL picture and a slow network or a newly
     * started decoder still gets the picture. A loader that stops behind the app lock or fails is never followed by
     * a URL fetch (#3635). A loader that fails yields no picture for its slot without cancelling the other.
     */
    private suspend fun resolveAvatars(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?> =
        withContext(Dispatchers.Default) {
            coroutineScope {
                val conversationAvatar =
                    async {
                        sources.storedConversationLoader?.let { boundedIdentityLoad(it) }
                            ?: sources.conversationBitmap
                            ?: sources.conversationLoader?.let { boundedIdentityLoad(it) }
                            ?: sources.conversationUrl.takeUnless { sources.hasConversationLoader }?.let { url ->
                                withContext(MediaTrustScope.element(sources.conversationDomain)) {
                                    avatarBitmapResolver(url)
                                }
                            }
                    }
                val senderAvatar =
                    async {
                        sources.senderBitmap
                            ?: sources.senderLoader?.let { boundedIdentityLoad(it) }
                            ?: sources.senderUrl.takeIf { sources.senderLoader == null }?.let { url ->
                                withContext(MediaTrustScope.element(sources.senderDomain)) {
                                    avatarBitmapResolver(url)
                                }
                            }
                    }
                conversationAvatar.await() to senderAvatar.await()
            }
        }

    /** Runs one late picture loader within its bound; a failure yields no picture, never a crash. */
    private suspend fun boundedIdentityLoad(load: suspend () -> Bitmap?): Bitmap? =
        withTimeoutOrNull(GROUP_IDENTITY_LOAD_TIMEOUT_MS) {
            runCatchingCancellable { load() }.getOrNull()
        }

    /** Rewrites one live card with avatars that changed after its post, preserving dismissal ownership. */
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
        // The enriched rewrite is a second platform write for the same card, so it takes a slot like the first,
        // but only while the card still shows this message. The lock section below checks it again.
        if (!isLiveCardForMessage(content, update)) return
        postPacer.awaitSlot()
        ConversationCardPostSynchronizer.withLock(
            content.notificationTag,
            content.notificationId,
            ConversationCardOp.SHOW_ENRICH,
        ) {
            if (!isPostStillAllowed() || !ConversationCardPostSynchronizer.isShowCurrent(showToken)) {
                return@withLock
            }
            val active =
                publisher.activeConversationCard(content.notificationTag, content.notificationId) ?: return@withLock
            if (!ConversationCardPostedRegistry.isCurrentPayload(
                    content.notificationTag,
                    content.notificationId,
                    active.extras.getString(EXTRA_PAYLOAD_REVISION),
                )
            ) {
                return@withLock
            }
            val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
            if (
                expectedMessageId != null &&
                conversationCardMessageIdHex(active) != expectedMessageId
            ) {
                return@withLock
            }
            messaging.shortcutId?.let { shortcutId ->
                conversationShortcuts.publishConversationShortcut(
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
                    isPublishAllowed = {
                        isPostStillAllowed() &&
                            ConversationCardPostSynchronizer.isShowCurrent(showToken) &&
                            publisher.canPublishPreviewShortcut(content, active)
                    },
                    identityContact = messaging.identityContact,
                )
            }
            // The late picture replaces the identity image and the sender together, for either card template.
            val identity =
                content.identityImage(
                    seed = messaging.shortcutId ?: content.notificationTag,
                    titleOverride = messaging.conversationTitleOverride,
                    conversationAvatar = conversationAvatarBitmap,
                    senderAvatar = senderAvatarBitmap,
                    contact = messaging.identityContact,
                )
            postEnrichedAvatarCard(content, active, enrichedSender, identity, showToken)
        }
    }

    /** Whether this message's card is still on the platform, the cheap check before spending a pacer slot. */
    private fun isLiveCardForMessage(
        content: LocalNotificationContent,
        update: NotificationUpdateFfi,
    ): Boolean {
        val active = publisher.activeConversationCard(content.notificationTag, content.notificationId)
        val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
        val sameMessage = expectedMessageId == null || conversationCardMessageIdHex(active) == expectedMessageId
        return active != null && sameMessage
    }

    /** Writes the late sender and identity picture to the still-live card, keeping its dismissal ownership. */
    private fun postEnrichedAvatarCard(
        content: LocalNotificationContent,
        active: Notification,
        sender: Person,
        identity: NotificationIdentityImage,
        showToken: ConversationCardShowToken,
    ) {
        val enriched = cardContent.enrichedIdentityNotification(active, sender, identity) ?: return
        val manager = NotificationManagerCompat.from(context)
        if (
            publisher.postNotificationSafely(
                manager,
                content.notificationTag,
                content.notificationId,
                enriched,
                mustBeLive = true,
            ) == NotificationCardWriteResult.WRITTEN
        ) {
            publisher.retainPostedCardUnlessDismissed(
                showToken,
                manager,
                content.notificationTag,
                content.notificationId,
            )
        }
    }

    /**
     * #3311: gives a card first drawn without its conversation or sender picture the picture that became available
     * after the post, as one silent rewrite of the same live card. AppState calls this once the post-write avatar
     * prewarm has run. Only a still-live, rich, preview-allowed card for this message, and the app's latest write
     * for its key, is rewritten, through the same enrichment rewrite a late picture uses, so the card keeps its key,
     * text, history, timestamp and actions and alerts only once. The conversation shortcut is republished with the
     * picture on the same path, which reports its use once more, since the snapshot changed. The one-report rule
     * holds for a message whose pictures are unchanged. Dismissed, read, replaced, redacted and hidden-preview
     * cards are left alone, and [isRefreshCurrent] carries the caller's account, lock and post fences up to the
     * platform write. No picture is fetched or decrypted unless the card still qualifies. Returns whether the card
     * was rewritten.
     */
    internal suspend fun refreshLateIdentityImage(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        pictures: NotificationIdentityPictureSources,
        directShareEligible: Boolean,
        isRefreshCurrent: () -> Boolean,
    ): Boolean =
        withContext(Dispatchers.Default) {
            // Nothing is fetched or decrypted for a card that can no longer take it, and the card is checked again
            // once the pictures arrive, since it may have been dismissed, read or replaced meanwhile.
            val refreshable = { isRefreshCurrent() && refreshableIdentityCard(update, content) != null }
            val arrived =
                if (refreshable()) {
                    (arrivedPictures(pictures) ?: unrecordedContactPictures(update, content, pictures))
                        ?.takeIf { refreshable() }
                } else {
                    null
                }
            if (arrived == null) {
                false
            } else {
                // A late picture is another platform write for the same card, so it takes a slot like the first,
                // but only once the card still qualifies. The lock section below checks it again.
                postPacer.awaitSlot()
                ConversationCardPostSynchronizer.withLock(
                    content.notificationTag,
                    content.notificationId,
                    ConversationCardOp.SHOW_ENRICH,
                ) {
                    val refresh =
                        LateIdentityRefresh(
                            update,
                            content,
                            arrived,
                            directShareEligible,
                            isRefreshCurrent,
                            pictures.identityContact,
                        )
                    refreshableIdentityCard(update, content)
                        ?.takeIf { isRefreshCurrent() }
                        ?.let { live ->
                            if (content.isReactionPreview) {
                                rewriteWithLateReactorPicture(refresh, live)
                            } else {
                                rewriteWithLatePictures(refresh, live)
                            }
                        } == true
                }
            }
        }

    /**
     * The card's final pictures once at least one it was drawn without has arrived, or null when none has. Nothing is
     * fetched while previews are hidden, and a picture the card already showed is never fetched again. A stored
     * conversation picture with the same pixels as the one the card shows changes nothing.
     */
    private suspend fun arrivedPictures(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?>? =
        if (!sources.hasMissingPicture || !NotificationPreviewPreferences.capture(context).allowed) {
            null
        } else {
            resolveAvatars(sources).takeIf { (conversation, sender) ->
                val shown = sources.conversationBitmap
                (conversation !== shown && conversation?.sameAs(shown) != true) || sender !== sources.senderBitmap
            }
        }

    /**
     * #3635: the card's own pictures when the late refresh found the peer whose private picture the card follows, and
     * the live card does not record them yet, as on an account whose chat list was not loaded at the post. The
     * rewrite records the peer, so the final write and every later Save or Clear apply their current choice.
     */
    private fun unrecordedContactPictures(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        sources: NotificationIdentityPictureSources,
    ): Pair<Bitmap?, Bitmap?>? {
        val contact = sources.identityContact ?: return null
        val recorded = refreshableIdentityCard(update, content)?.notification?.recordedIdentityContact()
        return (sources.conversationBitmap to sources.senderBitmap).takeIf { recorded != contact }
    }

    /**
     * The live card a late picture may rewrite: this message's card, the app's latest write for its key and not
     * dismissed, carrying an identity snapshot, and still allowed to show its preview. A redacted or hidden-preview
     * card fails [NotificationPreviewPreferences.canExpose], and a replaced or dismissed one fails the rest.
     */
    private fun refreshableIdentityCard(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
    ): StatusBarNotification? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val expectedMessageId = update.messageIdHex?.takeIf(String::isNotBlank)
        return publisher.activeNotification(manager, content.notificationTag, content.notificationId)?.takeIf { live ->
            val card = live.notification
            val generation = card.extras.getString(EXTRA_GENERATION)
            val latestWrite =
                generation != null &&
                    !NotificationCardGenerations.isDismissed(generation) &&
                    ConversationCardPostedRegistry.latestGeneration(live.tag.orEmpty(), live.id) == generation
            val sameMessage = expectedMessageId != null && conversationCardMessageIdHex(card) == expectedMessageId
            sameMessage &&
                latestWrite &&
                ConversationCardPostedRegistry.isCurrentPayload(
                    live.tag.orEmpty(),
                    live.id,
                    card.extras.getString(EXTRA_PAYLOAD_REVISION),
                ) &&
                card.recordedIdentityInputs() != null &&
                NotificationPreviewPreferences.canExpose(context, card, card)
        }
    }

    /**
     * Republishes the conversation shortcut with the late pictures and rewrites [live] through the enrichment rewrite.
     * The card keeps its own sender Person, so a sender renamed since the post keeps the new name, and its recorded
     * identity title and monogram seed, so only the pictures change.
     */
    private fun rewriteWithLatePictures(
        refresh: LateIdentityRefresh,
        live: StatusBarNotification,
    ): Boolean {
        val card = live.notification
        val inputs = card.recordedIdentityInputs()
        val livePerson = cardContent.liveSenderPerson(card, refresh.content.senderKey)
        if (inputs == null || livePerson == null) return false
        val (conversation, senderPicture) = refresh.pictures
        val sender =
            senderPicture?.let { livePerson.toBuilder().setIcon(IconCompat.createWithBitmap(it)).build() } ?: livePerson
        val (title, seed) = inputs
        val contact = card.recordedIdentityContact() ?: refresh.identityContact
        val identity =
            refresh.content.identityImage(
                seed = seed,
                titleOverride = title,
                conversationAvatar = conversation,
                senderAvatar = senderPicture,
                contact = contact,
            )
        card.shortcutId?.let { shortcutId ->
            conversationShortcuts.publishConversationShortcut(
                update = refresh.update,
                content = refresh.content,
                shortcutId = shortcutId,
                locusId = LocusIdCompat(shortcutId),
                conversationAvatarUrl = null,
                conversationAvatarBitmap = conversation,
                senderAvatarUrl = null,
                senderAvatarBitmap = senderPicture,
                sender = sender,
                directShareEligible = refresh.directShareEligible,
                isPublishAllowed = {
                    refresh.isCurrent() && publisher.canPublishPreviewShortcut(refresh.content, card)
                },
                identityContact = contact,
            )
        }
        return cardContent.enrichedIdentityNotification(card, sender, identity)?.let { rewritten ->
            postLateIdentityRewrite(refresh, live, rewritten)
        } == true
    }

    /**
     * #3635: a reaction card has no conversation template or shortcut, so a late picture rewrites only the reactor's
     * picture, keeping the card's reaction artwork, text and grouping.
     */
    private fun rewriteWithLateReactorPicture(
        refresh: LateIdentityRefresh,
        live: StatusBarNotification,
    ): Boolean {
        val picture = refresh.pictures.second ?: return false
        val rewritten = cardContent.reactorPictureNotification(live.notification, refresh.content.reactorImage(picture))
        return postLateIdentityRewrite(refresh, live, rewritten)
    }

    /** Writes one late picture rewrite over the same live card, under the refresh's fences up to the platform write. */
    private fun postLateIdentityRewrite(
        refresh: LateIdentityRefresh,
        live: StatusBarNotification,
        rewritten: Notification,
    ): Boolean =
        publisher.postNotificationSafely(
            NotificationManagerCompat.from(context),
            live.tag.orEmpty(),
            live.id,
            rewritten,
            recordedAtMs = dismissalTime(live),
            mustBeLive = true,
            finalPostAllowed = refresh.isCurrent,
        ) == NotificationCardWriteResult.WRITTEN

    /**
     * Applies the quiet flags at write time for a post that already decided to stay quiet, or whose ring a later
     * alert took while it waited for its pacer slot or card lock, and hands that claim back so the account may
     * ring later. The live same-key card is read here, under the card lock, so [quietWrite] sees the card the
     * platform is about to update. A retry that follows the presenter's own cancel of that card sets
     * [liveCardIsBeingCancelled], because the platform applies the cancel asynchronously and the card it still
     * lists is about to go, so the write is treated as a new post and keeps `setSilent`.
     */
    private fun NotificationCompat.Builder.settledForWrite(
        heldAlert: NotificationAlertReservation?,
        quiet: Boolean,
        replaceCurrentMessage: Boolean,
        notificationTag: String,
        notificationId: Int,
        liveCardIsBeingCancelled: Boolean = false,
    ): NotificationCompat.Builder {
        var superseded = false
        if (heldAlert != null && !heldAlert.stillHoldsTheRing()) {
            notificationDebug { "silent first post reason=Superseded" }
            heldAlert.release()
            superseded = true
        }
        if (!quiet && !superseded) return this
        val liveCard =
            if (liveCardIsBeingCancelled) {
                null
            } else {
                synchronized(UserEventNotificationGroup.mutationLock) {
                    publisher.activeConversationCard(notificationTag, notificationId)
                }
            }
        return quietWrite(replaceCurrentMessage, notificationId, liveCard)
    }

    /** Platform metadata carries only the existing opaque ownership scope, never a local account label. */
    private fun contactPictureAccountExtras(account: String): Bundle =
        Bundle().apply {
            putString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA, conversationShortcutAccountScope(account))
        }

    /** The same scoped callback serves initial publication and later platform-card reconciliation. */
    private fun currentContactAvatar(
        account: String,
        contact: String,
    ): Pair<Boolean, Bitmap?> {
        val scope = conversationShortcutAccountScope(account) ?: return false to null
        return contactAvatarOverride(scope, contact)
    }

    internal fun isGroupInviteNotificationActive(update: NotificationUpdateFfi): Boolean =
        retirement.isGroupInviteNotificationActive(
            update = update,
        )

    internal fun isNotificationUpdateCurrentForEnrichment(update: NotificationUpdateFfi): Boolean =
        retirement.isNotificationUpdateCurrentForEnrichment(
            update = update,
        )

    private fun stampConversationCardMessageId(
        builder: NotificationCompat.Builder,
        messageIdHex: String?,
    ) {
        val generationId = messageIdHex?.takeIf(String::isNotBlank) ?: return
        builder.addExtras(
            Bundle().apply {
                putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, generationId)
            },
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
    ) = retirement.cancel(notificationTag, notificationId)

    /** Reads only the acted card's platform identity before dismissal is allowed to inspect siblings. */
    internal fun conversationCardMessageIdHex(
        notificationTag: String,
        notificationId: Int,
    ): String? = publisher.conversationCardMessageIdHex(notificationTag, notificationId)

    internal fun cancelRepliedConversationCardIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        repliedMessageIdHex: String?,
    ) = retirement.cancelRepliedConversationCardIfSameGeneration(notificationTag, notificationId, repliedMessageIdHex)

    internal fun cancelConversationCardIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        actedMessageIdHex: String?,
    ) = retirement.cancelConversationCardIfSameGeneration(notificationTag, notificationId, actedMessageIdHex)

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
    ): Boolean = replyCompletion.markDirectReplyHandled(notificationTag, notificationId, replyText)

    /**
     * Clear a reaction chip's direct-reply lifetime extension only while the
     * live card still represents the message that offered the chip. A newer
     * message reuses the same notification key and must not inherit the old
     * reaction as RemoteInput history.
     */
    internal fun markReactionHandledIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        reactedMessageIdHex: String?,
        reaction: String,
    ): Boolean =
        replyCompletion.markReactionHandledIfSameGeneration(
            notificationTag,
            notificationId,
            reactedMessageIdHex,
            reaction,
        )

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
        replyCompletion.markDirectReplyFailed(
            notificationTag,
            notificationId,
            repliedMessageIdHex,
            failureNotice,
        )
}

private data class MessagingPostContext(
    val sender: Person,
    val conversationTitleOverride: String?,
    val shortcutId: String?,
    val conversationAvatarUrl: String?,
    val conversationAvatarBitmap: Bitmap?,
    val senderAvatarUrl: String?,
    val senderAvatarBitmap: Bitmap?,
    val identityContact: String?,
) {
    val requiresRemoteAvatarResolution: Boolean
        get() =
            (!conversationAvatarUrl.isNullOrBlank() && conversationAvatarBitmap == null) ||
                (!senderAvatarUrl.isNullOrBlank() && senderAvatarBitmap == null)
}

/**
 * One late identity-picture rewrite (#3311): the message, its posted content and the card's final pictures, and the
 * peer whose private picture the chat row shows, which the rewrite records when the card does not yet (#3635).
 */
private data class LateIdentityRefresh(
    val update: NotificationUpdateFfi,
    val content: LocalNotificationContent,
    val pictures: Pair<Bitmap?, Bitmap?>,
    val directShareEligible: Boolean,
    val isCurrent: () -> Boolean,
    val identityContact: String?,
)

private const val AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS = 2_500L

/** Bound for a late-refresh picture loader; it delays no other card, only this silent rewrite. */
private const val GROUP_IDENTITY_LOAD_TIMEOUT_MS = 15_000L
internal const val EXTRA_CONTENT_REDACTED = "dev.ipf.whitenoise.android.notify.content_redacted"

private val notificationEnrichmentScope =
    CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("notification-card-enrichment"),
    )

/**
 * The worker domain of a notification's conversation picture URL: a direct chat shows its sender's published
 * picture. A group's URL picture has bytes its host chooses, never a member, so it decodes single-use; a group's
 * encrypted image has no URL and comes from the conversation loader, in its conversation.
 */
internal fun notificationConversationDomain(update: NotificationUpdateFfi): MediaTrustDomain =
    if (update.isDm) {
        notificationSenderDomain(update)
    } else {
        MediaTrustDomain.Unique
    }

/** The worker domain of a notification's sender picture: a published kind-0 picture of the receiving account. */
internal fun notificationSenderDomain(update: NotificationUpdateFfi): MediaTrustDomain {
    val receiver = update.accountRef
    return MediaTrustDomain.publicProfiles(receiver)
}

/**
 * Resolves a picture URL to notification pixels, bounding the optional rich-card fetch. It decodes in the domain the
 * caller's scope declares for this slot, single-use when none is declared.
 */
private suspend fun resolveNotificationAvatarBitmap(url: String?): Bitmap? {
    val normalizedUrl = url?.takeUnless(String::isBlank)
    return normalizedUrl?.let { avatarUrl ->
        AvatarImageLoader.peekBitmap(avatarUrl)
            ?: withTimeoutOrNull(AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS) {
                // Enrichment is detached from the first alert. This bound now
                // limits only the optional rich-card update.
                AvatarImageLoader.loadBitmap(avatarUrl, MediaTrustScope.declared() ?: MediaTrustDomain.Unique)
            }
    }
}
