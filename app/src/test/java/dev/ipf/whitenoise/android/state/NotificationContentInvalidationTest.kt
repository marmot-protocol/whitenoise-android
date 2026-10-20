package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineRemoveReasonFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decisions behind deleted-content retraction (#3323) and the read-before-post gate (#2065). */
class NotificationContentInvalidationTest {
    /** A row reported under MDK's message-deleted trigger yields its account-scoped identity. */
    @Test
    fun deletedUpsertsAreRemovedMessages() {
        val change = TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.MESSAGE_DELETED, record("b", 2uL))
        val event = projection(change)

        assertEquals(listOf(RemovedNotificationMessage(ACCOUNT, GROUP, "b")), removedNotificationMessages(event))
    }

    /** A long-deleted row that is merely reprojected is not a fresh removal, so it cannot churn cards or the guard. */
    @Test
    fun reprojectedLongDeletedRowIsNotARemoval() {
        val event =
            projection(
                TimelineMessageChangeFfi.Upsert(
                    TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED,
                    record("a", 1uL, deleted = true),
                ),
            )

        assertTrue(removedNotificationMessages(event).isEmpty())
    }

    /** Invalidated, cleared and pruned rows are removed; a row that only left a query window is not. */
    @Test
    fun removeReasonsOtherThanQueryWindowAreRemovedMessages() {
        val event =
            projection(
                TimelineMessageChangeFfi.Remove("invalidated", TimelineRemoveReasonFfi.INVALIDATED),
                TimelineMessageChangeFfi.Remove("cleared", TimelineRemoveReasonFfi.CLEARED),
                TimelineMessageChangeFfi.Remove("pruned", TimelineRemoveReasonFfi.PRUNED),
                TimelineMessageChangeFfi.Remove("scrolled", TimelineRemoveReasonFfi.NO_LONGER_MATCHES_QUERY),
            )

        assertEquals(
            listOf("invalidated", "cleared", "pruned"),
            removedNotificationMessages(event).map { it.messageIdHex },
        )
    }

    /** Live messages, reactions and every non-projection event leave notification content alone. */
    @Test
    fun liveRowsAndOtherEventsAreNotRemoved() {
        val live =
            projection(
                TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.NEW_MESSAGE, record("a", 1uL)),
                TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.REACTION_ADDED, record("b", 2uL)),
            )

        assertTrue(removedNotificationMessages(live).isEmpty())
        assertTrue(removedNotificationMessages(MarmotEventFfi.GroupJoined(ACCOUNT, ACCOUNT, GROUP)).isEmpty())
    }

    /** The removal guard matches account, conversation and case-insensitive message, and never a missing id. */
    @Test
    fun invalidationsMatchTheExactScopedIdentity() {
        val invalidations = NotificationContentInvalidations(capacity = 4)
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, "AB12", "CAFE"))

        assertTrue(invalidations.isRemoved(ACCOUNT, "ab12", "cafe"))
        assertFalse(invalidations.isRemoved("account-b", "ab12", "cafe"))
        assertFalse(invalidations.isRemoved(ACCOUNT, "ab13", "cafe"))
        assertFalse(invalidations.isRemoved(ACCOUNT, "ab12", "beef"))
        assertFalse(invalidations.isRemoved(ACCOUNT, "ab12", null))
        assertFalse(invalidations.isRemoved(ACCOUNT, "ab12", ""))
    }

    /** Each account keeps its own bounded removals, so one account's burst never evicts another's. */
    @Test
    fun invalidationsAreBoundedPerAccount() {
        val invalidations = NotificationContentInvalidations(capacity = 1)
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, GROUP, "kept"))
        invalidations.markRemoved(RemovedNotificationMessage("account-b", GROUP, "b1"))
        invalidations.markRemoved(RemovedNotificationMessage("account-b", GROUP, "b2"))

        assertTrue(invalidations.isRemoved(ACCOUNT, GROUP, "kept"))
        assertFalse(invalidations.isRemoved("account-b", GROUP, "b1"))
        assertTrue(invalidations.isRemoved("account-b", GROUP, "b2"))
    }

    /** The guard stays bounded, evicting the oldest removal while a re-marked one stays recent. */
    @Test
    fun invalidationsEvictTheOldestBeyondCapacity() {
        val invalidations = NotificationContentInvalidations(capacity = 2)
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, GROUP, "a"))
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, GROUP, "b"))
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, GROUP, "a"))
        invalidations.markRemoved(RemovedNotificationMessage(ACCOUNT, GROUP, "c"))

        assertTrue(invalidations.isRemoved(ACCOUNT, GROUP, "a"))
        assertFalse(invalidations.isRemoved(ACCOUNT, GROUP, "b"))
        assertTrue(invalidations.isRemoved(ACCOUNT, GROUP, "c"))
    }

    /** The read marker itself, and a row at or before it while nothing is unread, are read. */
    @Test
    fun candidateCoveredByTheReadMarkerIsRead() {
        val row = readRow(markerId = "m", markerAt = 10uL)
        val page = listOf(record("older", 9uL), record("m", 10uL))

        assertEquals(NotificationCandidateState.Read, notificationCandidateState("m", row, page))
        assertEquals(NotificationCandidateState.Read, notificationCandidateState("older", row, page))
    }

    /** A newer message, or one the marker cannot be ordered against, still alerts. */
    @Test
    fun newerOrAmbiguousCandidatesStayUnread() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 1uL, firstUnread = "newer")
        val page = listOf(record("m", 10uL), record("newer", 11uL), record("late", 9uL))

        assertEquals(NotificationCandidateState.Unread, notificationCandidateState("newer", row, page))
        // Covered by the fallback order, but MDK's first unread row is the candidate itself.
        assertEquals(
            NotificationCandidateState.Unread,
            notificationCandidateState("late", row.copy(firstUnreadMessageIdHex = "late"), page),
        )
        // Covered by the fallback order while something is unread and the first unread row is off the page.
        assertEquals(
            NotificationCandidateState.Unread,
            notificationCandidateState("late", row.copy(firstUnreadMessageIdHex = "elsewhere"), page),
        )
    }

    /** A covered row that precedes MDK's first unread row is read even while a newer sibling is unread. */
    @Test
    fun coveredCandidateBeforeTheFirstUnreadRowIsRead() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 1uL, firstUnread = "newer")
        val page = listOf(record("older", 9uL), record("m", 10uL), record("newer", 11uL))

        assertEquals(NotificationCandidateState.Read, notificationCandidateState("older", row, page))
    }

    /** Without a chat-list row or read marker nothing has been read; off the page only an all-read chat is read. */
    @Test
    fun missingReadStateKeepsCandidatesUnread() {
        assertEquals(NotificationCandidateState.Unread, notificationCandidateState("a", null, null))
        assertEquals(NotificationCandidateState.Unread, notificationCandidateState("a", chatRow(GROUP), null))
        val row = readRow(markerId = "m", markerAt = 10uL)

        // A candidate MDK has not projected yet cannot be ordered against the marker, so it alerts.
        assertEquals(NotificationCandidateState.Unread, notificationCandidateState("new", row, emptyList()))
        assertEquals(
            NotificationCandidateState.Unread,
            notificationCandidateState("new", row.copy(unreadCount = 2uL, hasUnread = true), emptyList()),
        )
    }

    /** A candidate MDK reports deleted is removed whatever the read state. */
    @Test
    fun deletedCandidateIsRemoved() {
        val page = listOf(record("a", 12uL, deleted = true))

        assertEquals(NotificationCandidateState.Removed, notificationCandidateState("a", null, page))
    }

    /** A deleted or hidden unread row, or the card's own message, retracts the conversation's cards. */
    @Test
    fun reconciliationRetractsCardsCarryingRemovedContent() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 2uL, firstUnread = "x")
        val deletedUnread = listOf(record("m", 10uL), record("x", 11uL, deleted = true), record("y", 12uL))
        val hiddenUnread = listOf(record("m", 10uL), record("x", 11uL), record("y", 12uL))

        assertEquals(ActiveCardReconciliation.Retract, reconcile("y", row, deletedUnread))
        assertEquals(ActiveCardReconciliation.Retract, reconcile("y", row, hiddenUnread, hidden = setOf("X")))
        assertEquals(
            ActiveCardReconciliation.Retract,
            reconcile("y", row, listOf(record("y", 12uL, deleted = true))),
        )
    }

    /** A deleted row the reader already read cannot be on an unread card, so the card is kept. */
    @Test
    fun reconciliationKeepsCardsWhoseRemovedContentWasAlreadyRead() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 1uL, firstUnread = "y")
        val page = listOf(record("old", 9uL, deleted = true), record("m", 10uL), record("y", 12uL))

        assertEquals(ActiveCardReconciliation.Keep, reconcile("y", row, page))
    }

    /** An unread deletion received before the card's oldest entry was already retracted, so a later card is kept. */
    @Test
    fun reconciliationKeepsACardStartedAfterAnEarlierDeletion() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 2uL, firstUnread = "x")
        val page = listOf(record("m", 10uL), record("x", 100uL, deleted = true), record("y", 400uL))
        val cardStartedMs = 400_000L

        assertEquals(ActiveCardReconciliation.Keep, reconcile("y", row, page, since = cardStartedMs))
        // Received within the slack before the card's oldest entry, it may be on the card and still retracts it.
        val late = listOf(record("m", 10uL), record("x", 400uL - CARD_RECEIPT_SLACK_SECONDS.toULong(), deleted = true))
        assertEquals(ActiveCardReconciliation.Retract, reconcile("y", row, late, since = cardStartedMs))
    }

    /** A card whose newest message is already read is cancelled. */
    @Test
    fun reconciliationCancelsACardWhoseMessageIsRead() {
        val row = readRow(markerId = "y", markerAt = 12uL)
        val page = listOf(record("m", 10uL), record("y", 12uL))

        assertEquals(ActiveCardReconciliation.CancelRead, reconcile("y", row, page))
        assertEquals(ActiveCardReconciliation.Keep, reconcile(null, row, page))
    }

    /** An entry stays showable only while MDK has it, it is not deleted, hidden or read, and it carries an id. */
    @Test
    fun entryShowabilityRequiresAPresentUnreadUnhiddenRecord() {
        val row = readRow(markerId = "m", markerAt = 10uL, unread = 1uL, firstUnread = "u")
        val page = listOf(record("m", 10uL), record("u", 11uL), record("d", 12uL, deleted = true), record("h", 13uL))

        assertTrue(isNotificationEntryShowable("u", row, page, emptySet()))
        assertFalse(isNotificationEntryShowable("m", row, page, emptySet()))
        assertFalse(isNotificationEntryShowable("d", row, page, emptySet()))
        assertFalse(isNotificationEntryShowable("h", row, page, setOf("h")))
        assertFalse(isNotificationEntryShowable("missing", row, page, emptySet()))
        assertFalse(isNotificationEntryShowable(null, row, page, emptySet()))
    }

    /** Only a removal received while the cards held content can be a reaction card's quote. */
    @Test
    fun removalWhileShownIsBoundedByTheCardsOldestEntry() {
        val page = listOf(record("old", 10uL, deleted = true).copy(receivedAt = 100uL))

        assertFalse(hasRemovalWhileShown(page, emptySet(), cardContentSinceMs = 400_000L))
        assertTrue(hasRemovalWhileShown(page, emptySet(), cardContentSinceMs = 120_000L))
        assertTrue(hasRemovalWhileShown(listOf(record("h", 10uL).copy(receivedAt = 500uL)), setOf("h"), 400_000L))
    }

    /** MDK's read and manual-unread changes name their conversation; every other event names none. */
    @Test
    fun readStateChangesNameTheirConversation() {
        assertEquals(
            ACCOUNT to GROUP,
            readStateChangedConversation(chatListEvent(ChatListUpdateTriggerFfi.UNREAD_CHANGED)),
        )
        assertEquals(
            ACCOUNT to GROUP,
            readStateChangedConversation(chatListEvent(ChatListUpdateTriggerFfi.MANUAL_UNREAD_CHANGED)),
        )
        assertNull(readStateChangedConversation(chatListEvent(ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE)))
        assertNull(readStateChangedConversation(MarmotEventFfi.GroupJoined(ACCOUNT, ACCOUNT, GROUP)))
    }

    /** A chat-list projection event for the fixture conversation under [trigger]. */
    private fun chatListEvent(trigger: ChatListUpdateTriggerFfi): MarmotEventFfi =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = "account-hex",
                accountLabel = ACCOUNT,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = GROUP,
                        messages = emptyList(),
                        changes = emptyList(),
                        chatListRow = chatRow(GROUP),
                        chatListTrigger = trigger,
                    ),
            ),
        )

    /** Reconciles one card stamped with [stamped] whose oldest retained entry is at [since] milliseconds. */
    private fun reconcile(
        stamped: String?,
        row: ChatListRowFfi?,
        page: List<TimelineMessageRecordFfi>,
        hidden: Set<String> = emptySet(),
        since: Long? = null,
    ): ActiveCardReconciliation = reconcileActiveConversationCard(stamped, since, row, page, hidden)

    /** Wraps [changes] in one runtime projection event for the fixture account and group. */
    private fun projection(vararg changes: TimelineMessageChangeFfi): MarmotEventFfi =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = "account-hex",
                accountLabel = ACCOUNT,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = GROUP,
                        messages = emptyList(),
                        changes = changes.toList(),
                        chatListRow = null,
                        chatListTrigger = ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH,
                    ),
            ),
        )

    /** One timeline row at [timelineAt]. */
    private fun record(
        id: String,
        timelineAt: ULong,
        deleted: Boolean = false,
    ): TimelineMessageRecordFfi {
        val projected = requireNotNull(projectedTimelineMessage(timelineAppMessage(id, timelineAt)).projected)
        return projected.copy(deleted = deleted)
    }

    /** A chat-list row whose read marker is [markerId] at [markerAt]. */
    private fun readRow(
        markerId: String,
        markerAt: ULong,
        unread: ULong = 0uL,
        firstUnread: String? = null,
    ): ChatListRowFfi =
        chatRow(GROUP).copy(
            lastReadMessageIdHex = markerId,
            lastReadTimelineAt = markerAt,
            unreadCount = unread,
            hasUnread = unread > 0uL,
            firstUnreadMessageIdHex = firstUnread,
        )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "ab12"
    }
}
