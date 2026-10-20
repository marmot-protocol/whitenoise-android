package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi

/** One message whose notification content is no longer available: deleted, invalidated, pruned or hidden. */
internal data class RemovedNotificationMessage(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
)

/**
 * The messages a runtime event reports as newly removed (#3323): a row MDK reprojected under its message-deleted
 * trigger, and a row removed from the projection because it was invalidated, cleared or pruned. MDK owns accepted
 * deletion, so this only reads its typed projection changes and never parses deletion tags. A long-deleted row that
 * is merely reprojected, a row that stopped matching a query, and every other event yield nothing; a deletion this
 * process never saw live is found by reconciliation from the row's deleted flag instead.
 */
internal fun removedNotificationMessages(event: MarmotEventFfi): List<RemovedNotificationMessage> {
    val runtime = (event as? MarmotEventFfi.ProjectionUpdated)?.update ?: return emptyList()
    val update = runtime.update
    return update.changes.mapNotNull { change ->
        val messageIdHex =
            when (change) {
                is TimelineMessageChangeFfi.Upsert ->
                    change.message.messageIdHex.takeIf { change.trigger == TimelineUpdateTriggerFfi.MESSAGE_DELETED }
                is TimelineMessageChangeFfi.Remove ->
                    change.messageIdHex.takeIf { change.reason != TimelineRemoveReasonFfi.NO_LONGER_MATCHES_QUERY }
            }
        messageIdHex?.let { RemovedNotificationMessage(runtime.accountLabel, update.groupIdHex, it) }
    }
}

/**
 * Messages whose notification content was removed in this process, so a queued post or a delayed enrichment for one
 * of them can never put its content back on a card (#3323). It is a small bounded guard of identities only, never
 * content or protocol state: each account keeps its own most recent [capacity] removals, so one account's burst
 * cannot evict another's. Governed message candidates also read their timeline record before posting, covering
 * removals evicted from this guard or predating a restart. Reactions lack a quoted-target identity and bypass that
 * read; their quote protection is limited to retained removals in this process until MDK #2366 supplies that contract.
 */
internal class NotificationContentInvalidations(
    private val capacity: Int,
) {
    private val removedByAccount = HashMap<String, LinkedHashSet<String>>()

    /** Records [message] as removed. */
    fun markRemoved(message: RemovedNotificationMessage) {
        synchronized(this) {
            val removed = removedByAccount.getOrPut(message.accountRef) { LinkedHashSet() }
            val key = key(message.groupIdHex, message.messageIdHex)
            removed.remove(key)
            removed.add(key)
            if (removed.size > capacity) removed.remove(removed.first())
        }
    }

    /** Whether [messageIdHex] of [accountRef]'s [groupIdHex] was removed; a candidate without a message never is. */
    fun isRemoved(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String?,
    ): Boolean {
        val id = messageIdHex?.takeIf(String::isNotBlank) ?: return false
        return synchronized(this) { removedByAccount[accountRef]?.contains(key(groupIdHex, id)) == true }
    }

    /** Whether a retained removal makes an unidentified reaction quote unsafe in this account and conversation. */
    fun hasRemovalInConversation(
        accountRef: String,
        groupIdHex: String,
    ): Boolean {
        val prefix = "${groupIdHex.lowercase()}\u0000"
        return synchronized(this) { removedByAccount[accountRef]?.any { it.startsWith(prefix) } == true }
    }

    /** One identity per case-insensitive conversation and message within an account. */
    private fun key(
        groupIdHex: String,
        messageIdHex: String,
    ): String = "${groupIdHex.lowercase()}\u0000${messageIdHex.lowercase()}"
}

/** Whether a message notification candidate may still alert, decided at the final post boundary (#2065). */
internal enum class NotificationCandidateState(
    val logValue: String,
) {
    /** Still unread for its account and conversation, or not a message the read marker governs. */
    Unread("unread"),

    /** Covered by the conversation's durable read marker. */
    Read("read"),

    /** Deleted, invalidated or hidden since it was received. */
    Removed("removed"),

    /** The read state could not be established, so the stale candidate is withheld. */
    Unknown("unknown"),
}

/**
 * Decides a message candidate from MDK's durable read state for its own account and conversation (#2065). [row] is
 * the conversation's chat-list row and [page] its latest timeline page, or null when it was not read.
 *
 * MDK orders its read marker by a canonical key the binding does not expose, so the candidate counts as read only
 * when the exposed state agrees without ambiguity: it is the marker itself, or it sits at or before the marker in
 * MDK's fallback `(timeline_at, message_id)` order while either nothing is unread or it also precedes MDK's first
 * unread message. A row MDK reports deleted is removed. A candidate missing from the page, without a chat-list row
 * or without a read marker cannot be shown read, and every ambiguous case stays unread, so a genuinely new message
 * is never silenced.
 */
internal fun notificationCandidateState(
    messageIdHex: String,
    row: ChatListRowFfi?,
    page: List<TimelineMessageRecordFfi>?,
): NotificationCandidateState {
    val record = page?.recordFor(messageIdHex)
    val markerId = row?.lastReadMessageIdHex
    val read =
        when {
            record?.deleted == true -> return NotificationCandidateState.Removed
            row == null || markerId == null -> false
            markerId.equals(messageIdHex, ignoreCase = true) -> true
            // A candidate MDK has not projected yet, or one beyond the page, cannot be ordered, so it alerts.
            record == null -> false
            else -> isCoveredByReadMarker(row, record) && precedesFirstUnread(row, record, page)
        }
    return if (read) NotificationCandidateState.Read else NotificationCandidateState.Unread
}

/** Whether nothing is unread, or [record] precedes MDK's first unread row on [page]. */
private fun precedesFirstUnread(
    row: ChatListRowFfi,
    record: TimelineMessageRecordFfi,
    page: List<TimelineMessageRecordFfi>,
): Boolean {
    val nothingUnread = row.unreadCount == 0uL && !row.hasUnread
    val firstUnread = row.firstUnreadMessageIdHex?.let { page.recordFor(it) }
    return nothingUnread || (firstUnread != null && precedes(record, firstUnread))
}

/** Whether [record] sits at or before [row]'s read marker in MDK's fallback `(timeline_at, message_id)` order. */
internal fun isCoveredByReadMarker(
    row: ChatListRowFfi,
    record: TimelineMessageRecordFfi,
): Boolean {
    val markerId = row.lastReadMessageIdHex
    val markerAt = row.lastReadTimelineAt
    return markerId != null &&
        markerAt != null &&
        (
            record.timelineAt < markerAt ||
                (record.timelineAt == markerAt && record.messageIdHex.lowercase() <= markerId.lowercase())
        )
}

/** Whether [record] strictly precedes [other] in the fallback `(timeline_at, message_id)` order. */
private fun precedes(
    record: TimelineMessageRecordFfi,
    other: TimelineMessageRecordFfi,
): Boolean =
    record.timelineAt < other.timelineAt ||
        (record.timelineAt == other.timelineAt && record.messageIdHex.lowercase() < other.messageIdHex.lowercase())

/** The page's row for [messageIdHex], matched case-insensitively. */
private fun List<TimelineMessageRecordFfi>.recordFor(messageIdHex: String): TimelineMessageRecordFfi? =
    firstOrNull { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }

/** What restart reconciliation does with one conversation's active card (#3323, #2065). */
internal enum class ActiveCardReconciliation(
    val logValue: String,
) {
    /** Every message the card can carry is still showable and unread. */
    Keep("keep"),

    /** A message the card can carry was deleted or hidden, so the conversation's cards are retracted. */
    Retract("retract"),

    /** The card's newest message is already read, so that card alone is cancelled. */
    CancelRead("cancel-read"),
}

/**
 * Reconciles one active conversation card after a restart or reconnect from bounded authoritative state: the card's
 * stamped newest message, the conversation's chat-list [row] and its latest timeline [page]. A card only carries
 * messages the reader has not read, received no earlier than its oldest retained entry at [cardContentSinceMs]
 * (less [CARD_RECEIPT_SLACK_SECONDS] for the short wait between receipt and post). A deleted or locally
 * [hiddenMessageIds] row that is the stamped message, or that is unread and was received while the card held
 * content, may be in its history and retracts the card. A deletion already retracted before this card started is
 * not on it and keeps the card. A card whose newest message is read is cancelled; otherwise it is kept.
 */
internal fun reconcileActiveConversationCard(
    stampedMessageIdHex: String?,
    cardContentSinceMs: Long?,
    row: ChatListRowFfi?,
    page: List<TimelineMessageRecordFfi>,
    hiddenMessageIds: Set<String>,
): ActiveCardReconciliation {
    val stamped = stampedMessageIdHex?.takeIf(String::isNotBlank)
    val hidden = hiddenMessageIds.mapTo(HashSet()) { it.lowercase() }
    val sinceSeconds =
        cardContentSinceMs?.let { (it / MILLIS_PER_SECOND - CARD_RECEIPT_SLACK_SECONDS).coerceAtLeast(0L) }
    val removedOnCard =
        page.any { record ->
            val removed = record.deleted || record.messageIdHex.lowercase() in hidden
            val receivedWhileShown = sinceSeconds == null || record.receivedAt.toLong() >= sinceSeconds
            val unread = row == null || !isCoveredByReadMarker(row, record)
            val carried =
                record.messageIdHex.equals(stamped, ignoreCase = true) || (receivedWhileShown && unread)
            removed && carried
        }
    return when {
        removedOnCard -> ActiveCardReconciliation.Retract
        stamped != null && notificationCandidateState(stamped, row, page) == NotificationCandidateState.Read ->
            ActiveCardReconciliation.CancelRead
        else -> ActiveCardReconciliation.Keep
    }
}

private const val MILLIS_PER_SECOND = 1_000L

/** How long before a card's oldest retained entry a message on it may have been received. */
internal const val CARD_RECEIPT_SLACK_SECONDS = 60L

/** The bounded MDK state one conversation's notification cards are reconciled against. */
internal data class NotificationReconciliationState(
    val row: ChatListRowFfi?,
    val page: List<TimelineMessageRecordFfi>,
    val hidden: Set<String>,
)

/**
 * Whether a card entry for [messageIdHex] may stay shown (#3323, #2065): it carries an identity, MDK still has the
 * message in the bounded [page], and it is neither deleted, hidden on this device ([hidden], lowercase) nor read.
 * An entry outside the page cannot be verified, so it is not showable.
 */
internal fun isNotificationEntryShowable(
    messageIdHex: String?,
    row: ChatListRowFfi?,
    page: List<TimelineMessageRecordFfi>,
    hidden: Set<String>,
): Boolean {
    val record = messageIdHex?.let { id -> page.firstOrNull { it.messageIdHex.equals(id, ignoreCase = true) } }
    return record != null &&
        !record.deleted &&
        record.messageIdHex.lowercase() !in hidden &&
        notificationCandidateState(record.messageIdHex, row, page) == NotificationCandidateState.Unread
}

/**
 * Whether [page] holds a deleted or [hidden] row received while cards dated from [cardContentSinceMs] held content.
 * A reaction card quotes a target it does not identify, so such a removal may be its quote.
 */
internal fun hasRemovalWhileShown(
    page: List<TimelineMessageRecordFfi>,
    hidden: Set<String>,
    cardContentSinceMs: Long,
): Boolean {
    val sinceSeconds = (cardContentSinceMs / MILLIS_PER_SECOND - CARD_RECEIPT_SLACK_SECONDS).coerceAtLeast(0L)
    return page.any { record ->
        (record.deleted || record.messageIdHex.lowercase() in hidden) && record.receivedAt.toLong() >= sinceSeconds
    }
}

/** One conversation whose durable read state [event] reports changed, or null for any other event (#2065). */
internal fun readStateChangedConversation(event: MarmotEventFfi): Pair<String, String>? {
    val runtime = (event as? MarmotEventFfi.ProjectionUpdated)?.update ?: return null
    val trigger = runtime.update.chatListTrigger
    val readChanged =
        trigger == ChatListUpdateTriggerFfi.UNREAD_CHANGED || trigger == ChatListUpdateTriggerFfi.MANUAL_UNREAD_CHANGED
    return if (readChanged) runtime.accountLabel to runtime.update.groupIdHex else null
}
