package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.dismissalTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val CONVERSATION_DISMISSAL_MAX_ATTEMPTS = 3
private const val DISMISSAL_LOG_TAG_LENGTH = 16
private const val DISMISSAL_LOG_GROUP_LENGTH = 8

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

/** Opening-time boundary used to keep queued cleanup from consuming later notifications. */
internal data class ConversationDismissalRequest(
    val accountRef: String,
    val groupIdHex: String,
    val cutoffMs: Long,
)

/**
 * Invalidates and cancels existing cards through their shared tag/id synchronization.
 * Keeping the related cleanup operations together avoids another synchronization owner.
 */
@Suppress("TooManyFunctions")
internal class ConversationNotificationRetirement(
    private val context: Context,
    private val publisher: NotificationCardPublisher,
    private val tapTokens: NotificationTapTokens,
    private val nowMillis: () -> Long,
    private val dismissalRetryDelay: suspend () -> Unit,
    private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification>,
) {
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
        val initial = platform?.let(publisher::readActiveNotifications)
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

        val remaining = platform?.let(publisher::readActiveNotifications)
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
                publisher.cancelNotification(manager, key.tag, key.id)
                ConversationCardDismissalOutcome.CANCELLED
            } catch (expectedPlatformFailure: RuntimeException) {
                notificationDebug {
                    "dismiss cancel failed tag=${key.tag.take(DISMISSAL_LOG_TAG_LENGTH)} type=${expectedPlatformFailure.javaClass.simpleName}"
                }
                ConversationCardDismissalOutcome.FAILED
            }
        }

    /** Re-reads an opaque invite under its card lock before cancelling the opening-time generation. */
    @Suppress("LongParameterList")
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
                publisher.activeNotification(platform, invite.tag, invite.id)
                    ?: return@withLock ConversationCardDismissalOutcome.ABSENT
            if (dismissalTime(live) > cutoffMs || !live.matchesInvite(accountRef, groupIdHex)) {
                return@withLock ConversationCardDismissalOutcome.ABSENT
            }
            try {
                publisher.cancelNotification(compat, tag, live.id)
                ConversationCardPostedRegistry.clearPosted(tag, live.id)
                live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                ConversationCardDismissalOutcome.CANCELLED
            } catch (expectedPlatformFailure: RuntimeException) {
                notificationDebug { "invite dismiss failed type=${expectedPlatformFailure.javaClass.simpleName}" }
                ConversationCardDismissalOutcome.FAILED
            }
        }
    }

    /** Produces all deterministic cards owned by one account-scoped conversation. */
    fun conversationDismissalKeys(
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
            "dismissed group=${groupIdHex.take(DISMISSAL_LOG_GROUP_LENGTH)} attempts=$attempts failed=$failed " +
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
    @Suppress("LongParameterList")
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
    // Early exits preserve the existing best-effort sibling cleanup semantics.
    @Suppress("ReturnCount")
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
                    val live = publisher.activeNotification(manager, sbn.tag, sbn.id) ?: return@withLock
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
                        publisher.cancelNotification(compat, live.tag.orEmpty(), live.id)
                        live.tag?.takeIf(String::isNotBlank)?.let(tapTokens::remove)
                    }
                }
            }
        }
        return true
    }

    /**
     * Cancels one card under its lock. With [onlyIfLive] the platform cancel is skipped for a card that is
     * neither on screen nor written by this process since its last cancel, both checked inside the lock so a
     * post that lands first is still removed even before the platform lists it; the dismissal generation
     * advances either way so an in-flight post for the key cannot resurrect it afterwards.
     */
    fun cancelSynchronized(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
        onlyIfLive: Boolean = false,
    ) {
        ConversationCardPostSynchronizer.withLock(tag, id, ConversationCardOp.DISMISS_CANCEL) {
            ConversationCardPostSynchronizer.markDismissed(tag, id)
            val writtenByApp = ConversationCardPostedRegistry.clearPosted(tag, id)
            if (!onlyIfLive || writtenByApp || cardIsLive(tag, id)) publisher.cancelNotification(manager, tag, id)
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
            val live = publisher.activeNotification(manager, tag, id) ?: return@withLock
            if (dismissalTime(live) <= sinceMs) {
                ConversationCardPostedRegistry.clearPosted(tag, id)
                publisher.cancelNotification(compat, tag, id)
            }
        }
    }

    internal fun isGroupInviteNotificationActive(update: NotificationUpdateFfi): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active =
            manager?.let {
                publisher.activeNotification(it, update.notificationKey, LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
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
                publisher.conversationCardMessageIdHex(key.tag, key.id) == expectedMessageIdHex
            }
        }
    }

    fun cancel(
        notificationTag: String,
        notificationId: Int,
    ) {
        cancelSynchronized(NotificationManagerCompat.from(context), notificationTag, notificationId)
        notificationDebug { "cancelled tag=${notificationTag.take(16)} id=$notificationId" }
    }

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
            val liveCardMessageIdHex = publisher.conversationCardMessageIdHex(notificationTag, notificationId)
            ConversationCardPostSynchronizer.awaitTestBarrier(
                ConversationCardOp.CANCEL_IF_SAME_GENERATION,
                ConversationCardBarrier.AFTER_READ,
                notificationTag,
                notificationId,
            )
            if (shouldCancelRepliedConversationCard(actedMessageIdHex, liveCardMessageIdHex)) {
                ConversationCardPostedRegistry.clearPosted(notificationTag, notificationId)
                publisher.cancelNotification(NotificationManagerCompat.from(context), notificationTag, notificationId)
                notificationDebug { "cancelled tag=${notificationTag.take(16)} id=$notificationId" }
            }
        }
    }
}
