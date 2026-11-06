package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.dismissalTime

private const val RECONCILIATION_LOG_GROUP_LENGTH = 8

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

/** Removes invalid retained entries without changing the caller's message-validity policy. */
internal class ConversationNotificationContentReconciler(
    private val context: Context,
    private val publisher: NotificationCardPublisher,
    private val retirement: ConversationNotificationRetirement,
) {
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
            retirement.conversationDismissalKeys(accountRef, groupIdHex).associateWith { key ->
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
            "reconciled group=${groupIdHex.take(RECONCILIATION_LOG_GROUP_LENGTH)} " +
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
            val liveCard = publisher.activeNotification(manager, key.tag, key.id)
            val latestGeneration = ConversationCardPostedRegistry.latestGeneration(key.tag, key.id)
            val observedGeneration = liveCard?.notification?.extras?.getString(EXTRA_GENERATION)
            if (liveCard == null || (latestGeneration != null && latestGeneration != observedGeneration)) {
                // Android can omit an accepted post or expose an older sibling. Without the actual latest payload
                // there is no safe way to reconstruct its content: cancel rather than overwrite or report ABSENT.
                return@withLock cancelUnverifiedContentCard(compat, key, liveCard != null)
            }
            val live = liveCard.notification
            val entries =
                publisher.retainedEntryGroups(live).filter { entry ->
                    ConversationCardPostedRegistry.canCarryEntry(
                        key.tag,
                        key.id,
                        live.extras.getString(EXTRA_PAYLOAD_REVISION),
                        publisher.entryMessageId(entry),
                    )
                }
            val kept = entries.filter { isEntryValid(publisher.entryMessageId(it), it.first().timestamp) }
            try {
                when {
                    kept.size == entries.size && entries.isNotEmpty() -> CardContentOutcome.UNCHANGED
                    kept.isEmpty() || live.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true -> {
                        cancelUnverifiedContentCard(compat, key, platformHasCard = true)
                    }
                    else -> rewriteWithEntries(compat, key, live, kept, recordedAtMs = dismissalTime(liveCard))
                }
            } catch (expectedPlatformFailure: RuntimeException) {
                notificationDebug { "reconcile card failed type=${expectedPlatformFailure.javaClass.simpleName}" }
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
                    publisher.cancelNotification(compat, key.tag, key.id)
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
        val newestKeptId = publisher.entryMessageId(kept.last())
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
            publisher.postNotificationSafely(
                compat,
                key.tag,
                key.id,
                rewritten,
                recordedAtMs = recordedAtMs,
                mustBeLive = true,
            )
        val written = result == NotificationCardWriteResult.WRITTEN
        return if (written) CardContentOutcome.REBUILT else CardContentOutcome.FAILED
    }

    /** Lists the conversation cards this app currently shows, for bounded restart reconciliation. */
    internal fun activeConversationCards(): List<ActiveConversationCard> {
        val platform = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return publisher
            .readActiveNotifications(platform)
            .orEmpty()
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val isInvite = !extras?.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF).isNullOrBlank()
                val scope = LocalNotificationFormatter.conversationScopeOfCard(sbn.tag, sbn.id)
                scope?.takeUnless { isInvite }?.let { (accountRef, groupIdHex) ->
                    val entries = publisher.retainedEntryGroups(sbn.notification)
                    ActiveConversationCard(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        messageIdHex = conversationCardMessageIdHex(sbn.notification),
                        isMessageCard = sbn.id == LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID,
                        contentSinceMs = entries.flatten().minOfOrNull { it.timestamp } ?: sbn.notification.`when`,
                        entryMessageIds = entries.map(publisher::entryMessageId),
                    )
                }
            }
    }
}
