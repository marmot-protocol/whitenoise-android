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
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ReplyMediaKind
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private const val EXTRA_EXPANDED_SINGLE_MESSAGE_BODY =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_BODY"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_TIMESTAMP"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_SENDER"
private const val EXTRA_EXPANDED_SINGLE_MESSAGE_GROUP_TITLE =
    "dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_GROUP_TITLE"
private const val CONVERSATION_DISMISSAL_MAX_ATTEMPTS = 3
private const val CONVERSATION_DISMISSAL_RETRY_DELAY_MS = 25L

internal enum class ConversationCardDismissalOutcome {
    ABSENT,
    CANCELLED,
    FAILED,
    RESIDUAL,
}

/** Per-card diagnostics from one bounded conversation cancellation attempt. */
internal data class ConversationDismissalResult(
    val outcomes: Map<NotificationDismissalKey, ConversationCardDismissalOutcome>,
    val inspectionFailed: Boolean,
) {
    /** Whether every pre-existing target card is confirmed absent. */
    val complete: Boolean
        get() =
            !inspectionFailed &&
                outcomes.values.none {
                    it == ConversationCardDismissalOutcome.FAILED ||
                        it == ConversationCardDismissalOutcome.RESIDUAL
                }
}

/**
 * One app-owned conversation card in the tray: its stamped newest message, card kind, the time of its oldest
 * retained entry, and the message id of each retained entry, null where an entry carries none.
 */
internal data class ActiveConversationCard(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String?,
    val isMessageCard: Boolean,
    val contentSinceMs: Long,
    val entryMessageIds: List<String?>,
)

/** What reconciling one conversation card did, logged by count only. */
internal enum class CardContentOutcome(
    val logValue: String,
) {
    /** Every retained entry is still valid. */
    UNCHANGED("unchanged"),

    /** Rewritten silently with only its valid entries. */
    REBUILT("rebuilt"),

    /** Nothing valid remained, so the card was cancelled. */
    CANCELLED("cancelled"),

    /** No such card was shown. */
    ABSENT("absent"),

    /** The platform refused the read, write or cancel; reconciliation must run again. */
    FAILED("failed"),
}

/** Opaque identity of the exact accepted platform payload, separate from dismissal ownership. */
internal const val EXTRA_PAYLOAD_REVISION = "dev.ipf.whitenoise.android.notification.PAYLOAD_REVISION"

/** Payload keys a rewritten card drops so no removed entry's text can survive outside its MessagingStyle. */
private val REMOVED_CONTENT_EXTRAS =
    listOf(
        Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_TEXT_LINES,
        Notification.EXTRA_SUMMARY_TEXT,
        Notification.EXTRA_MESSAGES,
        Notification.EXTRA_HISTORIC_MESSAGES,
        EXTRA_EXPANDED_SINGLE_MESSAGE_BODY,
    )

/** Opening-time boundary used to keep queued cleanup from consuming later notifications. */
internal data class ConversationDismissalRequest(
    val accountRef: String,
    val groupIdHex: String,
    val cutoffMs: Long,
)

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
    private val quickReactionChoices: () -> List<String> = { notificationQuickReactionChoices(context) },
    private val notificationCanceller: (NotificationManagerCompat, String, Int) -> Unit = { manager, tag, id ->
        manager.cancel(tag, id)
    },
    private val postPacer: NotificationPostPacer = NotificationPostPacer.shared,
    private val alertBudget: NotificationAlertBudget = NotificationAlertBudget(),
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

    private val shortcutSnapshots = ConcurrentHashMap<String, ConversationShortcutSnapshot>()
    private val shortcutLastUsed = ConcurrentHashMap<String, Long>()
    private val shortcutAccessClock = AtomicLong()
    private val tapTokens = NotificationTapTokens.create(context)
    private val conversationVibrationPreferences = ConversationVibrationPreferences(context)
    private val profileNotificationOverrides by lazy { ProfileNotificationOverridePreferences(context) }
    private val profileNotificationChannels by lazy { ProfileNotificationChannels(context) }

    // First used from show()'s Default-dispatcher routing block, so defer the
    // SharedPreferences-backed construction instead of adding disk-backed work
    // to AppState's main-thread initialization.
    private val conversationNotificationRouting by lazy { ConversationNotificationRouting(context) }

    fun ensureChannels() {
        NotificationChannels.ensureChannels(context)
        reconcileNotificationGroup()
    }

    /** Rechecks OS cards on process start/resume and after auto-cancel notification taps. */
    fun reconcileNotificationGroup() = groupReconciliation()

    fun hideConversationShortcutsFromDirectShare() {
        hideConversationShortcutsFromDirectShare(context)
        shortcutSnapshots.clear()
        InFlightConversationShortcuts.forgetAllPublished()
        shortcutLastUsed.clear()
    }

    /** Clears platform/cache presentation while the caller holds an acknowledged credential-revocation fence. */
    fun clearRevokedConversationShortcutsForAccount(
        accountRef: String,
        includeUnscopedLegacy: Boolean,
    ) {
        runCatching { clearRevokedConversationShortcutsForAccount(context, accountRef, includeUnscopedLegacy) }
        val accountScope = conversationShortcutAccountScope(accountRef) ?: return
        shortcutSnapshots
            .filterValues { it.accountScope == accountScope }
            .keys
            .forEach { shortcutId ->
                forgetShortcutSnapshot(shortcutId)
                shortcutLastUsed.remove(shortcutId)
            }
    }

    /**
     * Forgets the published-shortcut snapshot of one locally removed conversation (abandon, reset or local delete).
     * A new conversation under the same group id then publishes its identity shortcut again before its first card,
     * instead of only reporting use of a shortcut that no longer exists. It takes the shared publication lock, so the
     * caller runs it off the main thread, inside the same lock section as the platform removal.
     */
    internal fun forgetConversationShortcut(
        accountRef: String,
        groupIdHex: String,
    ) {
        val shortcutId = conversationShortcutId(accountRef, groupIdHex) ?: return
        synchronized(UserEventNotificationGroup.mutationLock) {
            forgetShortcutSnapshot(shortcutId)
            shortcutLastUsed.remove(shortcutId)
        }
    }

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
                        val live = activeNotification(manager, snapshot.tag, snapshot.id) ?: return@withLock
                        val renamed =
                            renamedSenderNotification(
                                live.notification,
                                senderAccountIdHex,
                                senderName,
                            ) ?: return@withLock
                        if (
                            postNotificationSafely(
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
    ): ConversationDismissalRequest? {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return null
        ConversationCardPostSynchronizer.markConversationDismissed(ConversationCardScope(accountRef, groupIdHex))
        return ConversationDismissalRequest(accountRef, groupIdHex, nowMillis())
    }

    /** Captures and clears every opening-time card for one conversation. */
    suspend fun dismissConversationMessages(
        accountRef: String,
        groupIdHex: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        val request = captureConversationDismissal(accountRef, groupIdHex) ?: return false
        return dismissConversationMessages(request, dispatcher, shouldContinue)
    }

    /** Clears one captured opening boundary while its navigation owner remains current. */
    internal suspend fun dismissConversationMessages(
        request: ConversationDismissalRequest,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        shouldContinue: () -> Boolean = { true },
    ): Boolean =
        withContext(dispatcher) {
            if (!shouldContinue()) return@withContext false
            var result =
                dismissConversationMessagesOnce(request.accountRef, request.groupIdHex, request.cutoffMs)
            var attempts = 1
            var retryOwned = shouldContinue()
            while (!result.complete && attempts < CONVERSATION_DISMISSAL_MAX_ATTEMPTS && retryOwned) {
                dismissalRetryDelay()
                currentCoroutineContext().ensureActive()
                retryOwned = shouldContinue()
                if (retryOwned) {
                    result =
                        dismissConversationMessagesOnce(request.accountRef, request.groupIdHex, request.cutoffMs)
                    attempts += 1
                }
            }
            logConversationDismissal(request.groupIdHex, attempts, result)
            result.complete
        }

    /**
     * Runs one synchronous cancellation pass for callers that already own an
     * off-main lane. UI route cleanup uses [dismissConversationMessages] so it
     * can retry on its dedicated dispatcher.
     */
    fun dismissConversationMessagesImmediately(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        val request = captureConversationDismissal(accountRef, groupIdHex) ?: return false
        val result = dismissConversationMessagesOnce(accountRef, groupIdHex, request.cutoffMs)
        logConversationDismissal(groupIdHex, attempts = 1, result)
        return result.complete
    }

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
    ): Boolean {
        if (accountRef.isBlank() || groupIdHex.isBlank()) return false
        val compat = NotificationManagerCompat.from(context)
        val outcomes =
            conversationDismissalKeys(accountRef, groupIdHex).associateWith { key ->
                when (key.id) {
                    LocalNotificationFormatter.GROUP_MEMBERSHIP_NOTIFICATION_ID -> CardContentOutcome.UNCHANGED
                    LocalNotificationFormatter.REACTION_NOTIFICATION_ID ->
                        if (cancelReactionCards) {
                            reconcileCard(compat, key) { _, _ -> false }
                        } else {
                            CardContentOutcome.UNCHANGED
                        }
                    else -> reconcileCard(compat, key, isEntryValid)
                }
            }
        notificationDebug {
            "reconciled group=${groupIdHex.take(8)} " +
                CardContentOutcome.entries.joinToString(" ") { outcome ->
                    "${outcome.logValue}=${outcomes.values.count { it == outcome }}"
                }
        }
        return outcomes.values.none { it == CardContentOutcome.FAILED }
    }

    /** Reconciles one card under its lock: unchanged, rewritten with its valid entries, or cancelled. */
    private fun reconcileCard(
        compat: NotificationManagerCompat,
        key: NotificationDismissalKey,
        isEntryValid: (entryMessageIdHex: String?, shownAtMs: Long) -> Boolean,
    ): CardContentOutcome =
        // This label groups dismiss/reconcile test barriers only. Ordering uses the same tag/id lock;
        // a silent rewrite must not advance the dismissal generation of the entries it retains.
        ConversationCardPostSynchronizer.withLock(key.tag, key.id, ConversationCardOp.DISMISS_CANCEL) {
            val manager =
                context.getSystemService(NotificationManager::class.java)
                    ?: return@withLock CardContentOutcome.FAILED
            val liveCard = activeNotification(manager, key.tag, key.id)
            val latestGeneration = ConversationCardPostedRegistry.latestGeneration(key.tag, key.id)
            val observedGeneration = liveCard?.notification?.extras?.getString(EXTRA_GENERATION)
            if (liveCard == null || (latestGeneration != null && latestGeneration != observedGeneration)) {
                // Android can omit an accepted post or expose an older sibling. Without the actual latest payload
                // there is no safe way to reconstruct its content: cancel rather than overwrite or report ABSENT.
                return@withLock cancelUnverifiedContentCard(compat, key, liveCard != null)
            }
            val live = liveCard.notification
            val entries =
                retainedEntryGroups(live).filter { entry ->
                    ConversationCardPostedRegistry.canCarryEntry(
                        key.tag,
                        key.id,
                        live.extras.getString(EXTRA_PAYLOAD_REVISION),
                        entryMessageId(entry),
                    )
                }
            val kept = entries.filter { isEntryValid(entryMessageId(it), it.first().timestamp) }
            try {
                when {
                    kept.size == entries.size && entries.isNotEmpty() -> CardContentOutcome.UNCHANGED
                    kept.isEmpty() || live.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true -> {
                        cancelUnverifiedContentCard(compat, key, platformHasCard = true)
                    }
                    else -> rewriteWithEntries(compat, key, live, kept, recordedAtMs = dismissalTime(liveCard))
                }
            } catch (exception: RuntimeException) {
                notificationDebug { "reconcile card failed type=${exception.javaClass.simpleName}" }
                CardContentOutcome.FAILED
            }
        }

    /** Cancels an unobservable accepted card; absence is success only when this process has no accepted write. */
    private fun cancelUnverifiedContentCard(
        compat: NotificationManagerCompat,
        key: NotificationDismissalKey,
        platformHasCard: Boolean,
    ): CardContentOutcome =
        try {
            synchronized(UserEventNotificationGroup.mutationLock) {
                val pending = ConversationCardPostedRegistry.prepareCancellation(key.tag, key.id, platformHasCard)
                if (pending) {
                    ConversationCardPostSynchronizer.markDismissed(key.tag, key.id)
                    cancelNotification(compat, key.tag, key.id)
                    ConversationCardPostedRegistry.clearPosted(key.tag, key.id)
                    CardContentOutcome.CANCELLED
                } else {
                    CardContentOutcome.ABSENT
                }
            }
        } catch (_: RuntimeException) {
            CardContentOutcome.FAILED
        }

    /**
     * The card's retained entries as logical messages, newest last. A conversation card keeps its history in
     * MessagingStyle; a legacy expandable-text card, a plain card or a redacted card is one entry identified by
     * the card's stamped newest message id.
     */
    private fun retainedEntryGroups(notification: Notification): List<List<NotificationCompat.MessagingStyle.Message>> {
        val redacted = notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true
        val messages =
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.takeUnless { redacted || it.isEmpty() }
        if (messages != null) return notificationLogicalMessageGroups(messages)
        val single =
            expandedSingleMessage(notification.extras)?.firstOrNull()
                ?: NotificationCompat.MessagingStyle.Message(
                    notification.extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "",
                    notification.`when`,
                    null as Person?,
                )
        conversationCardMessageIdHex(notification)?.let {
            single.extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, it)
        }
        return listOf(listOf(single))
    }

    /** The message id stamped on one logical entry, or null for an entry posted before entries carried ids. */
    private fun entryMessageId(entry: List<NotificationCompat.MessagingStyle.Message>): String? =
        entry.firstNotNullOfOrNull { message ->
            message.extras.getString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX)?.takeIf(String::isNotBlank)
        }

    /**
     * Silently rewrites a live conversation card with only [kept] entries, so no removed entry's text, quote or
     * artwork survives anywhere in the payload. The newest kept entry becomes the card's stamped message; the
     * generation is unchanged, so the write lands only while that same card is still live. Copied actions are
     * removed when their newest-message target no longer matches the retained card. [recordedAtMs] preserves the
     * live card's dismissal age, so a cleanup captured before this silent rewrite can still remove it.
     */
    private fun rewriteWithEntries(
        compat: NotificationManagerCompat,
        key: NotificationDismissalKey,
        live: Notification,
        kept: List<List<NotificationCompat.MessagingStyle.Message>>,
        recordedAtMs: Long,
    ): CardContentOutcome {
        val previous = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(live)
        val messages = kept.flatten()
        val user: Person = previous?.user ?: Person.Builder().setName("").build()
        val style =
            NotificationCompat
                .MessagingStyle(user)
                .setConversationTitle(previous?.conversationTitle)
                .setGroupConversation(previous?.isGroupConversation == true)
        messages.forEach { style.addMessage(copiedMessage(it, replacement = null)) }
        val builder = NotificationCompat.Builder(context, live)
        REMOVED_CONTENT_EXTRAS.forEach(builder.extras::remove)
        val newestKeptId = entryMessageId(kept.last())
        if (newestKeptId != conversationCardMessageIdHex(live)) {
            builder.clearActions()
        }
        val rewritten =
            builder
                .setStyle(style)
                .setContentText(messages.last().text)
                .setTicker(null)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .addExtras(
                    Bundle().apply {
                        putString(
                            LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
                            newestKeptId,
                        )
                    },
                ).build()
        val result =
            postNotificationSafely(compat, key.tag, key.id, rewritten, recordedAtMs = recordedAtMs, mustBeLive = true)
        val written = result == NotificationCardWriteResult.WRITTEN
        return if (written) CardContentOutcome.REBUILT else CardContentOutcome.FAILED
    }

    /** Lists the conversation cards this app currently shows, for bounded restart reconciliation. */
    internal fun activeConversationCards(): List<ActiveConversationCard> {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return readActiveNotifications(platform)
            .orEmpty()
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val isInvite = !extras?.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF).isNullOrBlank()
                val scope = LocalNotificationFormatter.conversationScopeOfCard(sbn.tag, sbn.id)
                scope?.takeUnless { isInvite }?.let { (accountRef, groupIdHex) ->
                    val entries = retainedEntryGroups(sbn.notification)
                    ActiveConversationCard(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        messageIdHex = conversationCardMessageIdHex(sbn.notification),
                        isMessageCard = sbn.id == LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID,
                        contentSinceMs = entries.flatten().minOfOrNull { it.timestamp } ?: sbn.notification.`when`,
                        entryMessageIds = entries.map(::entryMessageId),
                    )
                }
            }
    }

    /** Cancels [update]'s card only while it still shows that same newest message, keeping newer siblings. */
    internal fun cancelNotificationUpdateCardIfCurrent(update: NotificationUpdateFfi) {
        val messageIdHex = update.messageIdHex?.takeIf(String::isNotBlank) ?: return
        val key = LocalNotificationFormatter.notificationDismissalKey(update)
        cancelConversationCardIfSameGeneration(key.tag, key.id, messageIdHex)
    }

    /** Runs one cancellation and verification pass without invalidating genuinely later registrations. */
    private fun dismissConversationMessagesOnce(
        accountRef: String,
        groupIdHex: String,
        cutoffMs: Long,
    ): ConversationDismissalResult {
        val platform = context.getSystemService(NotificationManager::class.java)
        val compat = NotificationManagerCompat.from(context)
        val initial = platform?.let(::readActiveNotifications)
        var inspectionFailed = platform == null
        val outcomes = linkedMapOf<NotificationDismissalKey, ConversationCardDismissalOutcome>()

        conversationDismissalKeys(accountRef, groupIdHex).forEach { key ->
            outcomes[key] = dismissDeterministicCard(compat, key, initial, cutoffMs)
        }
        initial
            ?.asSequence()
            ?.filter { dismissalTime(it) <= cutoffMs && it.matchesInvite(accountRef, groupIdHex) }
            ?.forEach { invite ->
                val key = NotificationDismissalKey(invite.tag.orEmpty(), invite.id)
                outcomes[key] = dismissInviteCard(platform, compat, invite, accountRef, groupIdHex, cutoffMs)
            }

        val remaining = platform?.let(::readActiveNotifications)
        if (remaining == null) {
            inspectionFailed = true
        } else {
            remaining
                .asSequence()
                .filter { dismissalTime(it) <= cutoffMs && it.matchesConversationCard(accountRef, groupIdHex) }
                .forEach {
                    val key = NotificationDismissalKey(it.tag.orEmpty(), it.id)
                    if (outcomes[key] != ConversationCardDismissalOutcome.FAILED) {
                        outcomes[key] = ConversationCardDismissalOutcome.RESIDUAL
                    }
                }
        }
        return ConversationDismissalResult(outcomes, inspectionFailed)
    }

    /** Cancels one deterministic card while isolating platform failures from sibling keys. */
    private fun dismissDeterministicCard(
        manager: NotificationManagerCompat,
        key: NotificationDismissalKey,
        active: Array<StatusBarNotification>?,
        cutoffMs: Long,
    ): ConversationCardDismissalOutcome =
        ConversationCardPostSynchronizer.withLock(key.tag, key.id, ConversationCardOp.DISMISS_CANCEL) {
            val writtenByApp =
                ConversationCardPostedRegistry.clearPosted(key.tag, key.id, cutoffMs)
            val listed = active?.any { it.tag == key.tag && it.id == key.id && dismissalTime(it) <= cutoffMs } == true
            if (active != null && !writtenByApp && !listed) return@withLock ConversationCardDismissalOutcome.ABSENT
            try {
                cancelNotification(manager, key.tag, key.id)
                ConversationCardDismissalOutcome.CANCELLED
            } catch (exception: RuntimeException) {
                notificationDebug {
                    "dismiss cancel failed tag=${key.tag.take(16)} type=${exception.javaClass.simpleName}"
                }
                ConversationCardDismissalOutcome.FAILED
            }
        }

    /** Re-reads an opaque invite under its card lock before cancelling the opening-time generation. */
    private fun dismissInviteCard(
        platform: NotificationManager,
        compat: NotificationManagerCompat,
        invite: StatusBarNotification,
        accountRef: String,
        groupIdHex: String,
        cutoffMs: Long,
    ): ConversationCardDismissalOutcome {
        val tag = invite.tag.orEmpty()
        return ConversationCardPostSynchronizer.withLock(tag, invite.id, ConversationCardOp.DISMISS_CANCEL) {
            val live =
                activeNotification(platform, invite.tag, invite.id)
                    ?: return@withLock ConversationCardDismissalOutcome.ABSENT
            if (dismissalTime(live) > cutoffMs || !live.matchesInvite(accountRef, groupIdHex)) {
                return@withLock ConversationCardDismissalOutcome.ABSENT
            }
            try {
                cancelNotification(compat, tag, live.id)
                ConversationCardPostedRegistry.clearPosted(tag, live.id)
                live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                ConversationCardDismissalOutcome.CANCELLED
            } catch (exception: RuntimeException) {
                notificationDebug { "invite dismiss failed type=${exception.javaClass.simpleName}" }
                ConversationCardDismissalOutcome.FAILED
            }
        }
    }

    /** Reads the system tray while preserving coroutine cancellation and converting platform faults to retry state. */
    private fun readActiveNotifications(manager: NotificationManager): Array<StatusBarNotification>? =
        try {
            activeNotificationsProvider(manager)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    /** Produces all deterministic cards owned by one account-scoped conversation. */
    private fun conversationDismissalKeys(
        accountRef: String,
        groupIdHex: String,
    ): List<NotificationDismissalKey> =
        listOf(
            LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.mentionDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.agentActivityDismissalKey(accountRef, groupIdHex),
            LocalNotificationFormatter.groupMembershipDismissalKey(accountRef, groupIdHex),
        )

    /** Emits privacy-safe convergence diagnostics without notification text or full identifiers. */
    private fun logConversationDismissal(
        groupIdHex: String,
        attempts: Int,
        result: ConversationDismissalResult,
    ) {
        val failed = result.outcomes.values.count { it == ConversationCardDismissalOutcome.FAILED }
        val residual = result.outcomes.values.count { it == ConversationCardDismissalOutcome.RESIDUAL }
        notificationDebug {
            "dismissed group=${groupIdHex.take(8)} attempts=$attempts failed=$failed " +
                "residual=$residual inspectionFailed=${result.inspectionFailed}"
        }
    }

    /** Whether this platform card is an invite owned by the requested account and group. */
    private fun StatusBarNotification.matchesInvite(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        val extras = notification.extras ?: return false
        return shouldDismissInvite(
            extraAccountRef = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
            extraGroupIdHex = extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
            accountRef = accountRef,
            groupIdHex = groupIdHex,
        )
    }

    /** Whether this card is any deterministic sibling or opaque invite for the conversation. */
    private fun StatusBarNotification.matchesConversationCard(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        val deterministic = conversationDismissalKeys(accountRef, groupIdHex).any { it.tag == tag && it.id == id }
        return deterministic || matchesInvite(accountRef, groupIdHex)
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
            if (isInvite && dismissalTime(sbn) <= sinceMs) {
                ConversationCardPostSynchronizer.withLock(
                    sbn.tag.orEmpty(),
                    sbn.id,
                    ConversationCardOp.DISMISS_CANCEL,
                ) {
                    val live = activeNotification(manager, sbn.tag, sbn.id) ?: return@withLock
                    val liveExtras = live.notification.extras ?: return@withLock
                    if (
                        dismissalTime(live) <= sinceMs &&
                        shouldDismissInvite(
                            extraAccountRef =
                                liveExtras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF),
                            extraGroupIdHex =
                                liveExtras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID),
                            accountRef = accountRef,
                            groupIdHex = groupIdHex,
                        )
                    ) {
                        cancelNotification(compat, live.tag.orEmpty(), live.id)
                        live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                    }
                }
            }
        }
        return true
    }

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
        // A first post rings only if its catch-up cohort has not rung yet and no alert rang moments ago
        // (#1579). The ring is held from here, so a concurrent first post for another conversation stays
        // silent, and handed back below if this card is never written. Enrichment rewrites arrive with
        // silentUpdate already set and never touch the budget.
        val alertReservation =
            if (silentUpdate) {
                null
            } else {
                alertBudget.reserve(nowMs = nowMillis(), isMention = update.isMention, accountRef = update.accountRef)
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
        var reactionInlineArtwork = false
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
                                            activeNotification(
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
                            .setContentIntent(conversationPendingIntent(update, notificationContent.notificationTag))
                            .setCategory(decision.category)
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
                    when (val style = decision.style) {
                        // Reactions get their own self-contained card (own tag/id on the
                        // reactions channel, see LocalNotificationFormatter) so they're muted
                        // independently of messages. Artwork replaces its shortcode in a decorated inline row;
                        // the card keeps its existing actions and never becomes a conversation.
                        NotificationStyleChoice.Plain -> {
                            builder.setContentTitle(notificationContent.title)
                            if (notificationContent.body.isNotBlank()) {
                                builder
                                    .setContentText(notificationContent.body)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.body))
                                reactionInlineArtwork =
                                    previewToken.allowed &&
                                    applyReactionNotificationArtwork(
                                        context,
                                        builder,
                                        notificationContent,
                                    )
                            } else {
                                // Expand the same sentence for long group names without repeating its title.
                                builder.setStyle(
                                    NotificationCompat
                                        .BigTextStyle()
                                        .setBigContentTitle("")
                                        .bigText(notificationContent.title),
                                )
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
                                        if (notificationContent.isGroupConversation) {
                                            null
                                        } else {
                                            senderAvatarBitmap ?: cachedAvatarBitmap(senderAvatarUrl)
                                        }
                                }
                            val sender =
                                notificationSenderPerson(
                                    notificationContent,
                                    resolvedSenderAvatarBitmap
                                        ?: if (redactContent || notificationContent.isGroupConversation) {
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
                                                        replyNotificationAction(actionTarget, quickReactions),
                                                    )
                                                // Reaction choices ride on Reply's RemoteInput so SystemUI
                                                // can render them inline.
                                                NotificationActionKind.REACT -> Unit
                                                NotificationActionKind.MARK_READ ->
                                                    builder.addAction(
                                                        markReadNotificationAction(actionTarget),
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
                                    senderAvatarUrl = senderAvatarUrl.takeUnless { notificationContent.isGroupConversation },
                                    senderAvatarBitmap = resolvedSenderAvatarBitmap,
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
                        }

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
                                                    existingConversationMessages(
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
                                            messagingStyle(
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
                                            postNotificationSafely(
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
                                            retainPostedCardUnlessDismissed(
                                                showToken,
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                        } else if (firstPostResult == NotificationCardWriteResult.FAILED) {
                                            cancelNotification(
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
                                                    messagingStyle(
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
                                                    postNotificationSafely(
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
                                                    retainPostedCardUnlessDismissed(
                                                        showToken,
                                                        notificationManager,
                                                        notificationContent.notificationTag,
                                                        notificationContent.notificationId,
                                                    )
                                                } else {
                                                    if (retryResult ==
                                                        NotificationCardWriteResult.FAILED
                                                    ) {
                                                        cancelNotification(
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
                                            postNotificationSafely(
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
                                            cancelNotification(
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
                                                postNotificationSafely(
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
                                            retainPostedCardUnlessDismissed(
                                                showToken,
                                                notificationManager,
                                                notificationContent.notificationTag,
                                                notificationContent.notificationId,
                                            )
                                        } else {
                                            if (postResult ==
                                                NotificationCardWriteResult.FAILED
                                            ) {
                                                cancelNotification(
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
                isPublishAllowed = isPublishAllowed,
                holdForPost = true,
            )
        }.takeIf { it }?.let { shortcutId }
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
            resolveMessagingAvatars(messaging)
        val avatarChanged =
            conversationAvatarBitmap !== messaging.conversationAvatarBitmap ||
                senderAvatarBitmap !== messaging.senderAvatarBitmap
        val stillOwned = isPostStillAllowed() && ConversationCardPostSynchronizer.isShowCurrent(showToken)
        if (!avatarChanged || !stillOwned) return

        val enrichedSender =
            notificationSenderPerson(
                content,
                senderAvatarBitmap ?: if (content.isGroupConversation) null else notificationMonogramBitmap(content.senderName, content.senderKey),
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

    /** Resolves the post's picture inputs, fetching only a slot that has a URL and no bitmap. */
    private suspend fun resolveMessagingAvatars(messaging: MessagingPostContext): Pair<Bitmap?, Bitmap?> =
        resolveAvatars(
            NotificationIdentityPictureSources(
                conversationBitmap = messaging.conversationAvatarBitmap,
                conversationUrl = messaging.conversationAvatarUrl,
                senderBitmap = messaging.senderAvatarBitmap,
                senderUrl = messaging.senderAvatarUrl,
            ),
        )

    /**
     * Resolves both picture slots concurrently off the main thread, keeping a present bitmap and never fetching it. A
     * conversation picture with no URL, such as a group's encrypted image, comes from the sources' loader. That loader
     * downloads and decrypts through MDK and runs only in the late refresh, after the enrichment permit is released,
     * so it gets a longer bound than a URL picture and a slow network or busy device still gets the picture. A loader
     * that fails yields no group picture without cancelling the sender slot.
     */
    private suspend fun resolveAvatars(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?> =
        withContext(Dispatchers.Default) {
            coroutineScope {
                val conversationAvatar =
                    async {
                        sources.conversationBitmap
                            ?: avatarBitmapResolver(sources.conversationUrl)
                            ?: sources.conversationLoader?.let { load ->
                                withTimeoutOrNull(GROUP_IDENTITY_LOAD_TIMEOUT_MS) {
                                    runCatchingCancellable { load() }.getOrNull()
                                }
                            }
                    }
                val senderAvatar = async { sources.senderBitmap ?: sources.senderUrl?.let { avatarBitmapResolver(it) } }
                conversationAvatar.await() to senderAvatar.await()
            }
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
            val active = activeConversationCard(content.notificationTag, content.notificationId) ?: return@withLock
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
                    isPublishAllowed = {
                        isPostStillAllowed() &&
                            ConversationCardPostSynchronizer.isShowCurrent(showToken) &&
                            canPublishPreviewShortcut(content, active)
                    },
                )
            }
            // The late picture replaces the identity image and the sender together, for either card template.
            val identity =
                content.identityImage(
                    seed = messaging.shortcutId ?: content.notificationTag,
                    titleOverride = messaging.conversationTitleOverride,
                    conversationAvatar = conversationAvatarBitmap,
                    senderAvatar = senderAvatarBitmap,
                )
            postEnrichedAvatarCard(content, active, enrichedSender, identity, showToken)
        }
    }

    /** Whether this message's card is still on the platform, the cheap check before spending a pacer slot. */
    private fun isLiveCardForMessage(
        content: LocalNotificationContent,
        update: NotificationUpdateFfi,
    ): Boolean {
        val active = activeConversationCard(content.notificationTag, content.notificationId)
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
        val enriched = enrichedIdentityNotification(active, sender, identity) ?: return
        val manager = NotificationManagerCompat.from(context)
        if (
            postNotificationSafely(
                manager,
                content.notificationTag,
                content.notificationId,
                enriched,
                mustBeLive = true,
            ) == NotificationCardWriteResult.WRITTEN
        ) {
            retainPostedCardUnlessDismissed(
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
            val visiblePictures =
                if (content.isGroupConversation) pictures.copy(senderBitmap = null, senderUrl = null) else pictures
            val arrived = if (refreshable()) arrivedPictures(visiblePictures)?.takeIf { refreshable() } else null
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
                    val refresh = LateIdentityRefresh(update, content, arrived, directShareEligible, isRefreshCurrent)
                    refreshableIdentityCard(update, content)
                        ?.takeIf { isRefreshCurrent() }
                        ?.let { live -> rewriteWithLatePictures(refresh, live) } == true
                }
            }
        }

    /**
     * The card's final pictures once at least one it was drawn without has arrived, or null when none has. Nothing is
     * fetched while previews are hidden, and a picture the card already showed is never fetched again.
     */
    private suspend fun arrivedPictures(sources: NotificationIdentityPictureSources): Pair<Bitmap?, Bitmap?>? =
        if (!sources.hasMissingPicture || !NotificationPreviewPreferences.capture(context).allowed) {
            null
        } else {
            resolveAvatars(sources).takeIf { (conversation, sender) ->
                conversation !== sources.conversationBitmap || sender !== sources.senderBitmap
            }
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
        return activeNotification(manager, content.notificationTag, content.notificationId)?.takeIf { live ->
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
        val livePerson = liveSenderPerson(card, refresh.content.senderKey)
        if (inputs == null || livePerson == null) return false
        val (conversation, senderPicture) = refresh.pictures
        val sender =
            senderPicture?.let { livePerson.toBuilder().setIcon(IconCompat.createWithBitmap(it)).build() } ?: livePerson
        val (title, seed) = inputs
        val identity =
            refresh.content.identityImage(
                seed = seed,
                titleOverride = title,
                conversationAvatar = conversation,
                senderAvatar = senderPicture,
            )
        card.shortcutId?.let { shortcutId ->
            publishConversationShortcut(
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
                isPublishAllowed = { refresh.isCurrent() && canPublishPreviewShortcut(refresh.content, card) },
            )
        }
        return enrichedIdentityNotification(card, sender, identity)?.let { rewritten ->
            postNotificationSafely(
                NotificationManagerCompat.from(context),
                live.tag.orEmpty(),
                live.id,
                rewritten,
                recordedAtMs = dismissalTime(live),
                mustBeLive = true,
                finalPostAllowed = refresh.isCurrent,
            ) == NotificationCardWriteResult.WRITTEN
        } == true
    }

    /** The live card's own Person for [senderKey], from the conversation template or a pre-upgrade text block. */
    private fun liveSenderPerson(
        card: Notification,
        senderKey: String,
    ): Person? =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(card)
            ?.let { style -> style.messages + style.historicMessages }
            ?.firstNotNullOfOrNull { message -> message.person?.takeIf { it.key == senderKey } }
            ?: card.extras
                ?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
                ?.let(Person::fromBundle)
                ?.takeIf { it.key == senderKey }

    /**
     * Rewrites the live card with the enriched sender and identity picture, for the conversation template and the
     * pre-upgrade long-message text block alike. It keeps the card's grouping eligible for heads-up while
     * suppressing a repeat alert, and leaves any other card shape untouched.
     */
    private fun enrichedIdentityNotification(
        active: Notification,
        sender: Person,
        identity: NotificationIdentityImage,
    ): Notification? {
        val builder =
            NotificationCompat
                .Builder(context, active)
                .setGroupAlertBehavior(active.groupAlertBehavior)
                .setOnlyAlertOnce(true)
        val enrichedStyle = enrichedMessagingStyle(active, sender)
        when {
            enrichedStyle != null -> builder.setStyle(enrichedStyle)
            active.extras?.containsKey(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER) == true ->
                builder.addExtras(Bundle().apply { putBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER, sender.toBundle()) })
            else -> return null
        }
        return builder.setConversationIdentityImage(identity).build()
    }

    /** Copies an active MessagingStyle while replacing the matching sender's rich Person metadata. */
    private fun enrichedMessagingStyle(
        notification: Notification,
        enrichedSender: Person,
    ): NotificationCompat.MessagingStyle? {
        val existing =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?: return null
        return copiedMessagingStyle(existing, enrichedSender)
    }

    /** Rebuilds an active card only when a stable sender key can be relabeled safely. */
    private fun renamedSenderNotification(
        notification: Notification,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        if (notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) {
            null
        } else {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.let { renamedMessagingNotification(notification, it, senderAccountIdHex, senderName) }
                ?: renamedExpandedSingleNotification(notification, senderAccountIdHex, senderName)
        }

    /** Rebuilds MessagingStyle history only when at least one stable sender key matches. */
    private fun renamedMessagingNotification(
        notification: Notification,
        style: NotificationCompat.MessagingStyle,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        (style.messages + style.historicMessages)
            .firstNotNullOfOrNull { message -> message.person?.takeIf { it.key == senderAccountIdHex } }
            ?.let { matchedPerson ->
                NotificationCompat
                    .Builder(context, notification)
                    .setStyle(copiedMessagingStyle(style, renamedPerson(matchedPerson, senderName)))
                    .setOnlyAlertOnce(true)
                    .build()
            }

    /** Rebuilds the single-message bridge while keeping a conversation title distinct from the sender. */
    private fun renamedExpandedSingleNotification(
        notification: Notification,
        senderAccountIdHex: String,
        senderName: String,
    ): Notification? =
        notification.extras
            ?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
            ?.let(Person::fromBundle)
            ?.takeIf { it.key == senderAccountIdHex }
            ?.let { sender ->
                val builder = NotificationCompat.Builder(context, notification)
                val existingTitle = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val groupTitle =
                    notification.extras
                        ?.getCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_GROUP_TITLE)
                        ?.toString()
                when {
                    existingTitle == sender.name.toString() -> builder.setContentTitle(senderName)
                    groupTitle != null &&
                        existingTitle ==
                        context.getString(R.string.notification_sender_in_group, sender.name, groupTitle) ->
                        builder.setContentTitle(
                            context.getString(R.string.notification_sender_in_group, senderName, groupTitle),
                        )
                }
                builder
                    .addExtras(
                        Bundle().apply {
                            putBundle(
                                EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER,
                                renamedPerson(sender, senderName).toBundle(),
                            )
                        },
                    ).setOnlyAlertOnce(true)
                    .build()
            }

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
                    activeConversationCard(notificationTag, notificationId)
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

    /** Refresh private Person pixels at the final write so a concurrent clear cannot revive an older snapshot. */
    @Suppress("ReturnCount") // Missing or redacted payloads must exit before reading private identity.
    private fun withCurrentContactAvatars(notification: Notification): Notification {
        if (notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) return notification
        val account =
            notification.extras?.getString(
                CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA,
            ) ?: return notification
        var style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            var changedAny = false
            val people = (style.messages + style.historicMessages).mapNotNull { it.person }.distinctBy { it.key }
            people.forEach { person ->
                val (changed, bitmap) = person.key?.let { contactAvatarOverride(account, it) } ?: (false to null)
                if (changed) {
                    changedAny = true
                    style = copiedMessagingStyle(checkNotNull(style), contactAvatarPerson(person, bitmap))
                }
            }
            return if (changedAny) {
                NotificationCompat.Builder(context, notification).setStyle(style).build()
            } else {
                notification
            }
        }
        val sender = notification.extras?.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)?.let(Person::fromBundle)
        val key = sender?.key ?: return notification
        val (changed, bitmap) = contactAvatarOverride(account, key)
        if (!changed) return notification
        return NotificationCompat
            .Builder(context, notification)
            .addExtras(
                Bundle().apply {
                    putBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER, contactAvatarPerson(sender, bitmap).toBundle())
                },
            ).build()
    }

    /** Refreshes the sender Person and a direct chat's identity picture from the current private contact choice. */
    private fun withCurrentContactPictures(notification: Notification): Notification {
        val people = withCurrentContactAvatars(notification)
        val account = people.extras?.getString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA)
        return if (account == null || people.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true) {
            people
        } else {
            withCurrentIdentityContact(context, people) { contact -> contactAvatarOverride(account, contact) }
        }
    }

    /** Writes one card and reports success without allowing observer failures to alter delivery. */
    private fun postNotificationSafely(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
        notification: Notification,
        onNotificationWritten: (() -> Unit)? = null,
        recordedAtMs: Long? = null,
        mustBeLive: Boolean = false,
        finalPostAllowed: (() -> Boolean)? = null,
        hiddenShortcut: (() -> ShortcutInfoCompat)? = null,
    ): NotificationCardWriteResult =
        try {
            if (finalPostAllowed != null) {
                ConversationCardPostSynchronizer.awaitTestBarrier(
                    ConversationCardOp.SHOW_NOTIFY,
                    ConversationCardBarrier.BEFORE_PLATFORM_WRITE,
                    tag,
                    id,
                )
            }
            val written =
                NotificationGroupReconciler.postChild(
                    context,
                    notification,
                    request = groupReconciliation,
                    isLive = { notificationWriteIsLive(notification, tag, id, mustBeLive, finalPostAllowed) },
                ) {
                    val payload = previewPayload(withCurrentContactPictures(notification), tag, id, hiddenShortcut)
                    // Rewrites of a card posted by the earlier expanded-choice build return to the standard template.
                    val standard = withoutGroupSenderPictures(context, withoutLegacyReactionRail(context, payload))
                    val revision = ConversationCardPostedRegistry.newPayloadRevision()
                    // A recovery builder can discard custom views and group alert behavior. Stamp a copy of
                    // the final platform payload so the revision describes exactly the card Android receives.
                    val outgoing = standard.clone().apply { extras.putString(EXTRA_PAYLOAD_REVISION, revision) }
                    notificationPoster(manager, tag, id, outgoing)
                    recordAcceptedPayload(tag, id, outgoing, recordedAtMs)
                }
            if (written) {
                runCatching { onNotificationWritten?.invoke() }
            }
            if (written) NotificationCardWriteResult.WRITTEN else NotificationCardWriteResult.REFUSED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: RuntimeException) {
            notificationDebug {
                "post failed tag=${tag.take(16)} type=${exception.javaClass.simpleName}"
            }
            NotificationCardWriteResult.FAILED
        }

    /** Keeps only bounded Android payload metadata, never message text or an independent removal/read policy. */
    private fun recordAcceptedPayload(
        tag: String,
        id: Int,
        payload: Notification,
        recordedAtMs: Long?,
    ) {
        ConversationCardPostedRegistry.markPosted(
            tag,
            id,
            recordedAtMs ?: System.currentTimeMillis(),
            payload.extras.getString(EXTRA_GENERATION),
            payload =
                ConversationCardPostedRegistry.PayloadSnapshot(
                    requireNotNull(payload.extras.getString(EXTRA_PAYLOAD_REVISION)),
                    retainedEntryGroups(payload).mapNotNull(::entryMessageId).toSet(),
                ),
        )
    }

    /** The caller's final fence still holds and, for a live-only write, the tray still shows this generation. */
    private fun notificationWriteIsLive(
        notification: Notification,
        tag: String,
        id: Int,
        mustBeLive: Boolean,
        finalPostAllowed: (() -> Boolean)?,
    ): Boolean =
        if (!ConversationCardPostedRegistry.canAcceptPost(tag, id) || finalPostAllowed?.invoke() == false) {
            false
        } else if (!mustBeLive) {
            true
        } else {
            val current =
                context.getSystemService(NotificationManager::class.java)?.let {
                    activeNotification(it, tag, id)
                }
            val candidateRevision = notification.extras.getString(EXTRA_PAYLOAD_REVISION)
            current != null &&
                ConversationCardPostedRegistry.isCurrentPayload(
                    tag,
                    id,
                    current.notification.extras.getString(EXTRA_PAYLOAD_REVISION),
                ) &&
                (
                    candidateRevision == null ||
                        ConversationCardPostedRegistry.isCurrentPayload(tag, id, candidateRevision)
                ) &&
                current.notification.extras.getString(EXTRA_GENERATION) ==
                notification.extras.getString(EXTRA_GENERATION)
        }

    private fun canPublishPreviewShortcut(
        content: LocalNotificationContent,
        expected: Notification,
    ): Boolean {
        val latest = activeConversationCard(content.notificationTag, content.notificationId) ?: return false
        val sameGeneration = latest.extras.getString(EXTRA_GENERATION) == expected.extras.getString(EXTRA_GENERATION)
        return sameGeneration &&
            ConversationCardPostedRegistry.isCurrentPayload(
                content.notificationTag,
                content.notificationId,
                latest.extras.getString(EXTRA_PAYLOAD_REVISION),
            ) &&
            NotificationPreviewPreferences.canExpose(context, latest, latest)
    }

    /** What Android receives: the rich card when its preview may be shown, otherwise the generic hidden card. */
    private fun previewPayload(
        notification: Notification,
        tag: String,
        id: Int,
        hiddenShortcut: (() -> ShortcutInfoCompat)?,
    ): Notification {
        val needsLiveProof =
            notification.extras.getBoolean(NotificationPreviewPreferences.EXTRA_CORRECTION) ||
                !NotificationPreviewPreferences.hasCurrentProvenance(context, notification)
        val current = if (needsLiveProof) activeConversationCard(tag, id) else null
        return if (NotificationPreviewPreferences.canExpose(context, notification, current)) {
            notification
        } else {
            // A hidden card leaves its conversation shortcut generic, so the next rich post must publish it again.
            notification.shortcutId?.let(::forgetShortcutSnapshot)
            notificationWithoutPreview(context, notification, shortcut = hiddenShortcut?.invoke())
        }
    }

    private fun cancelNotification(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
    ) {
        NotificationGroupReconciler.mutate(context, groupReconciliation) { notificationCanceller(manager, tag, id) }
    }

    /** Cancels a completed write when its conversation was dismissed during the platform call. */
    private fun retainPostedCardUnlessDismissed(
        showToken: ConversationCardShowToken,
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
    ): Boolean {
        if (ConversationCardPostSynchronizer.isShowNotDismissed(showToken)) return true
        ConversationCardPostedRegistry.clearPosted(tag, id)
        cancelNotification(manager, tag, id)
        return false
    }

    /**
     * Cancels one card under its lock. With [onlyIfLive] the platform cancel is skipped for a card that is
     * neither on screen nor written by this process since its last cancel, both checked inside the lock so a
     * post that lands first is still removed even before the platform lists it; the dismissal generation
     * advances either way so an in-flight post for the key cannot resurrect it afterwards.
     */
    private fun cancelSynchronized(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
        onlyIfLive: Boolean = false,
    ) {
        ConversationCardPostSynchronizer.withLock(tag, id, ConversationCardOp.DISMISS_CANCEL) {
            ConversationCardPostSynchronizer.markDismissed(tag, id)
            val writtenByApp = ConversationCardPostedRegistry.clearPosted(tag, id)
            if (!onlyIfLive || writtenByApp || cardIsLive(tag, id)) cancelNotification(manager, tag, id)
        }
    }

    /** Whether the platform currently shows this card; a failed read counts as live so the cancel still goes out. */
    private fun cardIsLive(
        tag: String,
        id: Int,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return try {
            activeNotificationsProvider(manager).any { it.tag == tag && it.id == id }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            true
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
            if (dismissalTime(live) <= sinceMs) {
                ConversationCardPostedRegistry.clearPosted(tag, id)
                cancelNotification(compat, tag, id)
            }
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

    internal fun isNotificationUpdateCurrentForEnrichment(update: NotificationUpdateFfi): Boolean {
        val expectedMessageIdHex = update.messageIdHex?.takeIf(String::isNotBlank)
        return when {
            update.trigger == NotificationTriggerFfi.GROUP_INVITE -> isGroupInviteNotificationActive(update)
            expectedMessageIdHex == null -> false
            else -> {
                val key = LocalNotificationFormatter.notificationDismissalKey(update)
                conversationCardMessageIdHex(key.tag, key.id) == expectedMessageIdHex
            }
        }
    }

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
    ) {
        cancelSynchronized(NotificationManagerCompat.from(context), notificationTag, notificationId)
        notificationDebug { "cancelled tag=${notificationTag.take(16)} id=$notificationId" }
    }

    /** Reads only the acted card's platform identity before dismissal is allowed to inspect siblings. */
    internal fun conversationCardMessageIdHex(
        notificationTag: String,
        notificationId: Int,
    ): String? =
        conversationCardMessageIdHex(
            runCatching {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                    ?.notification
            }.getOrNull(),
        )

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
                ConversationCardPostedRegistry.clearPosted(notificationTag, notificationId)
                cancelNotification(NotificationManagerCompat.from(context), notificationTag, notificationId)
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
    ): Boolean = markRemoteInputHandled(notificationTag, notificationId, expectedMessageIdHex = null, replyText)

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
    ): Boolean = markRemoteInputHandled(notificationTag, notificationId, reactedMessageIdHex, reaction)

    /**
     * Re-posts the live (tag, id) card with [handledText] as its RemoteInput history, ending
     * SystemUI's reply spinner. Skipped when [expectedMessageIdHex] no longer matches the live card.
     */
    @SuppressLint("MissingPermission")
    private fun markRemoteInputHandled(
        notificationTag: String,
        notificationId: Int,
        expectedMessageIdHex: String?,
        handledText: String,
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
            if (
                expectedMessageIdHex != null &&
                !shouldCancelRepliedConversationCard(
                    expectedMessageIdHex,
                    conversationCardMessageIdHex(active.notification),
                )
            ) {
                return@withLock false
            }
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
                        .apply {
                            extras.remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                            extras.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)
                        }.setRemoteInputHistory(arrayOf(handledText))
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build()
                        .withoutApi30RemoteInputHistoryItems()
                if (postNotificationSafely(
                        NotificationManagerCompat.from(context),
                        notificationTag,
                        notificationId,
                        resolved,
                        mustBeLive = true,
                    ) !=
                    NotificationCardWriteResult.WRITTEN
                ) {
                    return@runCatching false
                }
                notificationDebug { "remote-input-handled re-post tag=${notificationTag.take(16)} id=$notificationId" }
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
                        .apply {
                            extras.remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                            extras.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)
                        }.setRemoteInputHistory(arrayOf(failureNotice))
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build()
                        .withoutApi30RemoteInputHistoryItems()
                if (postNotificationSafely(
                        NotificationManagerCompat.from(context),
                        notificationTag,
                        notificationId,
                        resolved,
                        mustBeLive = true,
                    ) !=
                    NotificationCardWriteResult.WRITTEN
                ) {
                    return@runCatching false
                }
                notificationDebug { "reply-failed re-post tag=${notificationTag.take(16)} id=$notificationId" }
                true
            }.getOrDefault(false)
        }

    /**
     * Accumulates one conversation under a stable card while normalizing carried lines for the current sender.
     * Attachment data, timestamps, other senders, and the bounded history order remain unchanged.
     */
    private fun messagingStyle(
        content: LocalNotificationContent,
        conversationTitleOverride: String?,
        historyCap: Int,
        carriedHistory: List<NotificationCompat.MessagingStyle.Message>?,
        sender: Person,
        newMessageTimestampMs: Long,
        emojiArtwork: android.net.Uri? = null,
        messageIdHex: String? = null,
    ): NotificationCompat.MessagingStyle {
        val self =
            Person
                .Builder()
                .setName(content.selfName)
                .setKey(content.selfKey)
                .build()
        // Cap carried-forward history; the extracted style is otherwise re-serialized unbounded across Binder on every post.
        val style = NotificationCompat.MessagingStyle(self)
        val newMessages =
            notificationEmojiMessages(
                content.body,
                newMessageTimestampMs,
                sender,
                emojiArtwork,
                context.getString(R.string.custom_emoji),
            )
        messageIdHex?.takeIf(String::isNotBlank)?.let { id ->
            newMessages.forEach { it.extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, id) }
        }
        carriedHistory
            ?.let { capNotificationLogicalHistory(it, historyCap, MAX_NOTIFICATION_MESSAGE_HISTORY - newMessages.size) }
            ?.forEach { message ->
                style.addMessage(
                    copiedMessage(
                        message = message,
                        replacement = sender.takeIf { replacement -> message.person?.key == replacement.key },
                        boundedText = true,
                    ),
                )
            }
        style.isGroupConversation = content.isGroupConversation
        // Prefer the caller-resolved title (chat-list parity, e.g. "Group of N
        // people" for unnamed groups) over the often-empty payload group name.
        (conversationTitleOverride?.takeIf { it.isNotBlank() } ?: content.conversationTitle)?.let { style.conversationTitle = it }
        newMessages.forEach(style::addMessage)
        return style
    }

    private fun activeConversationCard(
        tag: String,
        id: Int,
    ): Notification? =
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.let(activeNotificationsProvider)
                ?.firstOrNull { it.tag == tag && it.id == id }
                ?.notification
        }.getOrNull()

    private fun existingConversationMessages(
        tag: String,
        id: Int,
        replacingMessageIdHex: String? = null,
    ): List<NotificationCompat.MessagingStyle.Message>? {
        val existing =
            activeConversationCard(tag, id)
                ?.takeUnless { it.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true }
                ?.takeIf { NotificationPreviewPreferences.canRetainPreview(context, it) }
                ?: return null
        val messages =
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(existing)
                ?.messages
                ?: expandedSingleMessage(existing.extras)
        val retained =
            messages
                ?.let(::notificationLogicalMessageGroups)
                ?.filter { entry ->
                    ConversationCardPostedRegistry.canCarryEntry(
                        tag,
                        id,
                        existing.extras.getString(EXTRA_PAYLOAD_REVISION),
                        entryMessageId(entry),
                    )
                }?.flatten()
        return if (
            replacingMessageIdHex != null &&
            conversationCardMessageIdHex(existing) == replacingMessageIdHex
        ) {
            retained?.let(::dropLastNotificationLogicalMessage)
        } else {
            retained
        }
    }

    // Read posted pre-upgrade BigTextStyle cards until their next message replaces them with MessagingStyle.
    private fun expandedSingleMessage(extras: Bundle?): List<NotificationCompat.MessagingStyle.Message>? =
        extras?.let { bundle ->
            val body = bundle.getCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_BODY)
            val senderBundle = bundle.getBundle(EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER)
            val timestamp = bundle.getLong(EXTRA_EXPANDED_SINGLE_MESSAGE_TIMESTAMP, Long.MIN_VALUE)
            if (body == null || senderBundle == null || timestamp == Long.MIN_VALUE) {
                null
            } else {
                listOf(
                    NotificationCompat.MessagingStyle
                        .Message(
                            boundedNotificationMessageText(body),
                            timestamp,
                            Person.fromBundle(senderBundle),
                        ).apply {
                            // The long first message is one entry identified by the card's stamped message.
                            bundle.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX)?.let {
                                extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, it)
                            }
                        },
                )
            }
        }

    /** Cached private pixels replace the protocol-sourced bitmaps; when none are cached, the public ones stay. */
    private fun ownedShortcutAvatars(
        update: NotificationUpdateFfi,
        conversationAvatarBitmap: Bitmap?,
        senderAvatarBitmap: Bitmap?,
        sender: Person,
    ): Triple<Bitmap?, Bitmap?, Person> {
        if (!update.isDm) return Triple(conversationAvatarBitmap, null, sender)
        val (override, currentAvatar) = currentContactAvatar(update.accountRef, update.sender.accountIdHex)
        if (!override) return Triple(conversationAvatarBitmap, senderAvatarBitmap, sender)
        val senderAvatar = currentAvatar ?: senderAvatarBitmap
        val conversationAvatar =
            if (update.isDm) currentAvatar ?: conversationAvatarBitmap else conversationAvatarBitmap
        return Triple(conversationAvatar, senderAvatar, contactAvatarPerson(sender, senderAvatar))
    }

    /**
     * Revalidates account-private icon overrides at the serialized shortcut write while retaining route and
     * preview ownership. An unchanged snapshot only reports the shortcut used. A changed one, such as a picture
     * that arrived after the post, is republished and reported used once more. A cached snapshot counts as published
     * only while the platform still lists the shortcut, so a dropped push or a shortcut another publisher removed is
     * published again instead of leaving every later card naming a missing shortcut. With [holdForPost] the
     * published shortcut is held in [InFlightConversationShortcuts], under the same lock as the write, for the
     * caller's coming card write. Returns whether it was held.
     */
    @Suppress("LongMethod") // One serialized platform write retains its alert, routing and privacy snapshot.
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
        isPublishAllowed: () -> Boolean,
        holdForPost: Boolean = false,
    ): Boolean =
        runCatching {
            val (ownedConversationAvatar, ownedSenderAvatar, ownedSender) =
                ownedShortcutAvatars(update, conversationAvatarBitmap, senderAvatarBitmap, sender)
            val candidateTitle = content.conversationTitle ?: content.title
            val existingTitle =
                shortcutSnapshots[shortcutId]?.longLabel
                    ?: ShortcutManagerCompat
                        .getDynamicShortcuts(context)
                        .firstOrNull { it.id == shortcutId }
                        ?.longLabel
                        ?.toString()
            val title = preferredConversationShortcutTitle(candidateTitle, existingTitle)
            val accountScope = conversationShortcutAccountScope(update.accountRef) ?: return false
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
                    avatarGenerationId = ownedConversationAvatar?.generationId,
                    senderAvatarUrl = senderAvatarUrl.takeIf { update.isDm },
                    senderAvatarGenerationId = ownedSenderAvatar?.generationId,
                    directShareEligible = directShareEligible,
                    previewRevision = NotificationPreviewPreferences.capture(context).revision,
                )
            shortcutLastUsed[shortcutId] = shortcutAccessClock.incrementAndGet()
            if (shortcutSnapshots[shortcutId] == snapshot) {
                reuseCachedShortcut(shortcutId, snapshot, isPublishAllowed, holdForPost)?.let { return it }
            }
            val intent = conversationShortcutOpenIntent(update, content)
            val build = { shareEligible: Boolean ->
                conversationShortcutInfo(
                    shortcutId = shortcutId,
                    snapshot = snapshot,
                    intent = intent,
                    locusId = locusId,
                    sender = notificationShortcutSender(content, ownedSender),
                    conversationAvatarBitmap = ownedConversationAvatar,
                    directShareEligible = shareEligible,
                )
            }
            val shortcut = build(directShareEligible)
            // A refresh that retains this shortcut for its card keeps a twin that is never a share target (#3322).
            val retainable = if (directShareEligible) build(false) else shortcut
            synchronized(UserEventNotificationGroup.mutationLock) {
                // A cap filled by protected shortcuts skips the publication, and the card keeps the existing fallback.
                if (isPublishAllowed() && pruneConversationShortcutsBeforePublish(shortcutId)) {
                    val (currentChoice, pixels) = currentContactAvatar(update.accountRef, update.sender.accountIdHex)
                    val preview = NotificationPreviewPreferences.capture(context)
                    val finish = { built: ShortcutInfoCompat ->
                        built.extras?.let { stampShortcutPreview(preview, it) }
                        stampContactPictureShortcut(built, update.sender.accountIdHex, update.isDm)
                        if (!shortcutPreviewAllowed(context, built)) {
                            genericNotificationShortcut(context, built)
                        } else if (currentChoice) {
                            withContactPictureIcon(
                                context,
                                built,
                                update.sender.accountIdHex,
                                pixels,
                                initialGroupPresentation =
                                    initialGroupShortcutPresentation(
                                        content,
                                        title,
                                        shortcutId,
                                        ownedConversationAvatar,
                                        ownedSender,
                                    ),
                            )
                        } else {
                            built
                        }
                    }
                    val currentShortcut = finish(shortcut)
                    val retainedShortcut = if (retainable === shortcut) currentShortcut else finish(retainable)
                    shortcutPublisher(currentShortcut)
                    shortcutSnapshots[shortcutId] = snapshot
                    InFlightConversationShortcuts.consumeIdentityLost(shortcutId)
                    ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                    InFlightConversationShortcuts.rememberPublished(shortcutId, currentShortcut, retainedShortcut)
                    if (holdForPost) InFlightConversationShortcuts.hold(shortcutId)
                    holdForPost
                } else {
                    false
                }
            }
        }.onFailure {
            notificationDebug { "conversation shortcut skipped group=${update.groupIdHex.take(8)}" }
        }.getOrDefault(false)

    /**
     * Reuses an unchanged cached publication. Re-read under the lock: a local removal may have forgotten the snapshot
     * after the caller's check, or the platform may have lost the shortcut, and then it must be published again rather
     * than only reported used. Returns whether the reused shortcut was held, or null when it must be republished.
     */
    private fun reuseCachedShortcut(
        shortcutId: String,
        snapshot: ConversationShortcutSnapshot,
        isPublishAllowed: () -> Boolean,
        holdForPost: Boolean,
    ): Boolean? =
        synchronized(UserEventNotificationGroup.mutationLock) {
            // A Direct Share refresh that rebuilt the shortcut without its picture leaves the snapshot stale too.
            val published =
                shortcutSnapshots[shortcutId] == snapshot &&
                    shortcutId in dynamicShortcutIds() &&
                    !InFlightConversationShortcuts.consumeIdentityLost(shortcutId)
            if (!published) {
                if (shortcutSnapshots.remove(shortcutId, snapshot)) {
                    InFlightConversationShortcuts.forgetPublished(shortcutId)
                }
                null
            } else if (isPublishAllowed()) {
                ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
                if (holdForPost) InFlightConversationShortcuts.hold(shortcutId)
                holdForPost
            } else {
                false
            }
        }

    /** Forgets one shortcut's publication snapshot together with the rich copy kept for holds of it. */
    private fun forgetShortcutSnapshot(shortcutId: String) {
        shortcutSnapshots.remove(shortcutId)
        InFlightConversationShortcuts.forgetPublished(shortcutId)
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

    /**
     * Makes room for [shortcutId] under the platform's per-activity cap, which counts every dynamic shortcut and every
     * manifest shortcut, by removing the least recently used app-owned conversation shortcuts. Shortcuts backing a
     * live card or held for a pending card write are never removed. Returns false when they leave no room, so the
     * caller skips the publication instead of letting the platform evict one of them.
     */
    private fun pruneConversationShortcutsBeforePublish(shortcutId: String): Boolean {
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        if (maxShortcuts <= 0) return true
        val manifestCount =
            runCatching {
                ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size
            }.getOrDefault(0)
        val protectedIds = liveCardShortcutIds() + InFlightConversationShortcuts.ids()
        val idsToRemove =
            conversationShortcutPrunePlan(
                dynamicShortcutIds(),
                manifestCount,
                maxShortcuts,
                shortcutId,
                protectedIds,
                shortcutLastUsed,
            )
        if (!idsToRemove.isNullOrEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, idsToRemove)
            idsToRemove.forEach {
                forgetShortcutSnapshot(it)
                shortcutLastUsed.remove(it)
            }
        }
        return idsToRemove != null
    }

    /** Conversation shortcut ids that this app's live cards name, read through the injected notification source. */
    private fun liveCardShortcutIds(): Set<String> =
        runCatching {
            context
                .getSystemService(NotificationManager::class.java)
                ?.let(activeNotificationsProvider)
                .orEmpty()
                .mapNotNullTo(mutableSetOf()) { it.notification.shortcutId?.takeIf(::isConversationShortcutId) }
        }.getOrDefault(emptySet())

    private fun replyNotificationAction(
        actionTarget: NotificationActionTarget,
        reactionChoices: List<String>,
    ): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(NotificationActions.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.message))
                .setChoices(reactionChoices.toTypedArray())
                .setAllowFreeFormInput(true)
                .setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED)
                .build()
        return NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_whitenoise,
                context.getString(R.string.reply),
                actionPendingIntent(
                    actionTarget,
                    NotificationActionKind.REPLY,
                    acceptsInlineReactionChoices = reactionChoices.isNotEmpty(),
                ),
            ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
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
        acceptsInlineReactionChoices: Boolean = false,
    ): PendingIntent {
        val actionIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                NotificationActions.applyToIntent(
                    this,
                    kind,
                    actionTarget,
                    acceptsInlineReactionChoices,
                )
            }
        val mutableFlag =
            if (kind == NotificationActionKind.REPLY || kind == NotificationActionKind.REACT) {
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
        val acceptsDraft = update.trigger == NotificationTriggerFfi.NEW_MESSAGE && update.reactionEmoji.isNullOrBlank()
        val target = NotificationNavigation.fromUpdate(update)
        val signature = target?.takeIf { acceptsDraft }?.let { tapTokens.signatureFor(tag, it) }
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Key the tap target on the notification tag (per-conversation
                // for messages) so the accumulating card always reopens the
                // same conversation. PendingIntents compare by URI, not extras.
                target?.let { destination ->
                    if (signature != null) {
                        NotificationNavigation.applyBoundToIntent(this, destination, tag, signature)
                    } else {
                        NotificationNavigation.applyToIntent(this, destination, tag, tapTokens.tokenFor(tag))
                    }
                }
            }
        return PendingIntent.getActivity(
            context,
            NotificationNavigation.requestCode(tag),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (signature != null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
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

/** One late identity-picture rewrite (#3311): the message, its posted content and the card's final pictures. */
private data class LateIdentityRefresh(
    val update: NotificationUpdateFfi,
    val content: LocalNotificationContent,
    val pictures: Pair<Bitmap?, Bitmap?>,
    val directShareEligible: Boolean,
    val isCurrent: () -> Boolean,
)

/** Pixel generations invalidate image-only changes without retaining another copy of bitmap memory. */
private data class ConversationShortcutSnapshot(
    val shortcutId: String,
    val accountScope: String,
    val shortLabel: String,
    val longLabel: String,
    val notificationTag: String,
    val senderName: String,
    val senderKey: String,
    val avatarUrl: String?,
    val avatarGenerationId: Int?,
    val senderAvatarUrl: String?,
    val senderAvatarGenerationId: Int?,
    val directShareEligible: Boolean,
    val previewRevision: Long,
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
    redacted: Boolean = false,
): Person =
    Person
        .Builder()
        .setName(content.senderName)
        .setKey(content.senderKey)
        .apply {
            if (!redacted) {
                val icon =
                    if (content.isGroupConversation && content.senderKey.isNotBlank()) {
                        groupSenderIcon()
                    } else {
                        avatarBitmap?.let(IconCompat::createWithBitmap)
                    }
                icon?.let(::setIcon)
            }
        }.build()

private const val NOTIFICATION_MONOGRAM_SIZE_PX = 128
private const val AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS = 2_500L

/** Bound for a group's encrypted picture in the late refresh; it delays no other card, only this silent rewrite. */
private const val GROUP_IDENTITY_LOAD_TIMEOUT_MS = 15_000L
internal const val EXTRA_CONTENT_REDACTED = "dev.ipf.whitenoise.android.notify.content_redacted"

private val notificationEnrichmentScope =
    CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("notification-card-enrichment"),
    )

/** Resolves a public avatar URL to notification pixels, bounding the optional rich-card fetch. */
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

private const val CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA =
    "dev.ipf.whitenoise.android.notify.contact_picture_account_scope"
