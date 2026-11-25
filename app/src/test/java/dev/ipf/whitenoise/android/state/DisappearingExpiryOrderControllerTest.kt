package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controller-level regressions ensuring disappearing-message read deferral, foreground sweep rows,
 * and read anchoring follow chronological display order instead of append-only `timelineOrder`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class DisappearingExpiryOrderControllerTest {
    /** Re-projecting the last-read row keeps newer unread received rows deferred and visible. */
    @Test
    fun reprojectingLastReadRowKeepsNewerUnreadReceivedRowsDeferred() =
        runBlocking {
            val first = disappearingRow(FIRST, timelineAt = 100uL, expiresAt = LIVE_EXPIRES_AT)
            val second = disappearingRow(SECOND, timelineAt = 200uL, expiresAt = PAST_EXPIRES_AT)
            val third = disappearingRow(THIRD, timelineAt = 300uL, expiresAt = PAST_EXPIRES_AT)
            val controller =
                buildController(
                    appState = standaloneAppState(),
                    readRow = readCursorRow(lastReadId = FIRST, lastReadAt = 100uL),
                )
            try {
                controller.applyTimelinePage(
                    timelinePage(first, second, third),
                    replaceWindow = true,
                    updatePagination = true,
                )
                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(controller))

                val edited = first.copy(plaintext = "edited last read")
                val trigger = TimelineUpdateTriggerFfi.MESSAGE_EDITED_OR_REPROJECTED
                controller.applyTimelineChanges(listOf(TimelineMessageChangeFfi.Upsert(trigger, edited)))

                assertEquals(listOf("msg:$SECOND", "msg:$THIRD", "msg:$FIRST"), controller.timelineOrder)
                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(controller))
                assertFalse(checkNotNull(controller.retainedExpiryRow(FIRST)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(controller.retainedExpiryRow(SECOND)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(controller.retainedExpiryRow(THIRD)).second.deferSendTimeExpiry)
                assertFalse(controller.isRetainedRowGone(SECOND))
                assertFalse(controller.isRetainedRowGone(THIRD))
                assertEquals(2, controller.foregroundSweepExpiryRows().count { it.deferSendTimeExpiry })
            } finally {
                controller.onCleared()
            }
        }

    /** Markdown hydration on open with mixed hydrated and unhydrated rows keeps unread rows deferred. */
    @Test
    fun markdownHydrationOnOpenWithMixedRowsKeepsUnreadReceivedRowsDeferred() =
        runBlocking {
            val first = disappearingRow(FIRST, timelineAt = 100uL, expiresAt = LIVE_EXPIRES_AT, plaintext = "read text")
            val second = disappearingRow(SECOND, timelineAt = 200uL, expiresAt = PAST_EXPIRES_AT, plaintext = "")
            val third = disappearingRow(THIRD, timelineAt = 300uL, expiresAt = PAST_EXPIRES_AT, plaintext = "unread")
            val readRow = readCursorRow(lastReadId = FIRST, lastReadAt = 100uL)
            val fixture = readFixture(readRow)
            val subscription = ScriptedConversationTimelineSubscription(timelinePage(first, second, third))
            val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), conversationTimelineTestGroup())
            var controller: ConversationController? = null
            try {
                fixture.bootstrap()
                fixture.appState.liveSubscriptionOverrides.conversation = scripted.subscriptions
                val mounted = buildController(fixture.appState, readRow, startOnConstruction = true)
                controller = mounted
                awaitConversationCondition {
                    mounted.timelineRecords[FIRST]
                        ?.contentTokens
                        ?.blocks
                        ?.isNotEmpty() == true &&
                        mounted.timelineRecords[THIRD]
                            ?.contentTokens
                            ?.blocks
                            ?.isNotEmpty() == true
                }

                assertTrue(
                    mounted.timelineRecords
                        .getValue(SECOND)
                        .contentTokens
                        .blocks
                        .isEmpty(),
                )
                assertEquals(listOf("msg:$SECOND", "msg:$FIRST", "msg:$THIRD"), mounted.timelineOrder)
                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(mounted))
                assertFalse(checkNotNull(mounted.retainedExpiryRow(FIRST)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(mounted.retainedExpiryRow(SECOND)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(mounted.retainedExpiryRow(THIRD)).second.deferSendTimeExpiry)
            } finally {
                controller?.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripted)
                fixture.close()
            }
        }

    /** An older-page EXTEND sorts already-read older rows before the anchor and unread newer rows after it. */
    @Test
    fun olderPageExtendLoadPartitionsReadOlderRowsAndUnreadNewerRowsForSweep() =
        runBlocking {
            val first = disappearingRow(FIRST, timelineAt = 100uL, expiresAt = PAST_EXPIRES_AT)
            val second = disappearingRow(SECOND, timelineAt = 200uL, expiresAt = LIVE_EXPIRES_AT)
            val third = disappearingRow(THIRD, timelineAt = 300uL, expiresAt = PAST_EXPIRES_AT)
            val controller =
                buildController(
                    appState = standaloneAppState(),
                    readRow = readCursorRow(lastReadId = SECOND, lastReadAt = 200uL),
                )
            try {
                controller.applyTimelinePage(
                    timelinePage(second, third),
                    replaceWindow = true,
                    updatePagination = true,
                )
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))

                controller.applyTimelinePage(
                    TimelinePageFfi(listOf(first, second, third), hasMoreBefore = false, hasMoreAfter = false),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf("msg:$SECOND", "msg:$THIRD", "msg:$FIRST"), controller.timelineOrder)
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(controller.isRetainedRowGone(FIRST))
                assertFalse(controller.isRetainedRowGone(THIRD))
                assertFalse(checkNotNull(controller.retainedExpiryRow(FIRST)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(controller.retainedExpiryRow(THIRD)).second.deferSendTimeExpiry)

                val sweepRows = controller.foregroundSweepExpiryRows()
                assertEquals(listOf(100uL, 200uL, 300uL), sweepRows.map { it.timelineAtSeconds })
                assertFalse(sweepRows[0].deferSendTimeExpiry)
                assertTrue(DisappearingMessageSweep.isLocallyExpired(NOW_MILLIS, sweepRows[0]))
                assertTrue(sweepRows[2].deferSendTimeExpiry)
                assertFalse(DisappearingMessageSweep.isLocallyExpired(NOW_MILLIS, sweepRows[2]))
            } finally {
                controller.onCleared()
            }
        }

    /** Marking an older row read after EXTEND stamps only that row and older rows, never newer unread rows. */
    @Test
    fun markReadUpToOlderRowAfterExtendStampsOnlyOlderRowsNotNewerUnreadRows() =
        runBlocking {
            val first = disappearingRow(FIRST, timelineAt = 100uL, expiresAt = PAST_EXPIRES_AT)
            val second = disappearingRow(SECOND, timelineAt = 200uL, expiresAt = PAST_EXPIRES_AT)
            val third = disappearingRow(THIRD, timelineAt = 300uL, expiresAt = PAST_EXPIRES_AT)
            val fourth = disappearingRow(FOURTH, timelineAt = 400uL, expiresAt = PAST_EXPIRES_AT)
            val initialRow = readCursorRow(lastReadId = null, lastReadAt = null)
            val markedRow = readCursorRow(lastReadId = SECOND, lastReadAt = 200uL)
            val fixture = readFixture(initialRow) { markedRow }
            var controller: ConversationController? = null
            try {
                fixture.bootstrap()
                val mounted = buildController(fixture.appState, initialRow, attachVisible = true)
                controller = mounted
                mounted.applyTimelinePage(
                    timelinePage(third, fourth),
                    replaceWindow = true,
                    updatePagination = true,
                )
                mounted.applyTimelinePage(
                    TimelinePageFfi(listOf(first, second, third, fourth), hasMoreBefore = false, hasMoreAfter = false),
                    replaceWindow = false,
                    updatePagination = true,
                )
                assertEquals(
                    listOf("msg:$THIRD", "msg:$FOURTH", "msg:$FIRST", "msg:$SECOND"),
                    mounted.timelineOrder,
                )

                mounted.markReadUpTo(SECOND)

                assertEquals(NOW_SECONDS, mounted.readAnchoredAtSeconds[FIRST])
                assertEquals(NOW_SECONDS, mounted.readAnchoredAtSeconds[SECOND])
                assertNull(mounted.readAnchoredAtSeconds[THIRD])
                assertNull(mounted.readAnchoredAtSeconds[FOURTH])
                assertTrue(checkNotNull(mounted.retainedExpiryRow(THIRD)).second.deferSendTimeExpiry)
                assertTrue(checkNotNull(mounted.retainedExpiryRow(FOURTH)).second.deferSendTimeExpiry)
            } finally {
                fixture.appState.clearActiveConversation()
                controller?.let(fixture.appState::detachConversationController)
                controller?.onCleared()
                fixture.close()
            }
        }

    /** Optimistic rows take raw-id precedence for matching item ids and keep expiry deferred. */
    @Test
    fun chronologicalHelperPreservesOptimisticFirstOccurrenceForDuplicateIds() =
        runBlocking {
            assertOptimisticAnchorDefersExpiry(optimisticItemId = "msg:$SECOND")
        }

    /** Optimistic rows take raw-id precedence even when item ids differ from the projection. */
    @Test
    fun chronologicalHelperPreservesOptimisticPrecedenceWhenItemIdsDiffer() =
        runBlocking {
            assertOptimisticAnchorDefersExpiry(optimisticItemId = "optimistic-$SECOND")
        }

    /** A projected deadline still schedules the sweep when a deadline-free optimistic copy wins its raw-id position. */
    @Test
    fun projectedDeadlineSchedulesSweepWhenOptimisticCopyWinsPosition() =
        runBlocking {
            val second = disappearingRow(SECOND, timelineAt = 100uL, expiresAt = NOW_SECONDS + 1uL)
            val appState = standaloneAppState()
            val controller = buildController(appState, readCursorRow(lastReadId = SECOND, lastReadAt = 100uL))
            try {
                controller.applyTimelinePage(timelinePage(second), replaceWindow = true, updatePagination = true)
                val optimisticId = "optimistic-$SECOND"
                val record =
                    TimelineProjector.toAppMessageRecord(second).copy(
                        recordedAt = 150uL,
                        retentionSeconds = null,
                        retentionExpiresAt = null,
                    )
                appState.optimisticMessages(controller.boundAccountRef, GROUP_ID)[optimisticId] =
                    TimelineMessage(
                        id = optimisticId,
                        record = record,
                        status = MessageStatus.Sent,
                        projected = null,
                        timelineOrder = 1uL,
                        retentionAtSendSeconds = null,
                    )

                val rows = controller.foregroundSweepExpiryRows()

                assertEquals(1_000L, DisappearingMessageSweep.nextForegroundSweepDelayMillis(NOW_MILLIS, rows))
            } finally {
                controller.onCleared()
            }
        }

    /** Repeated lookups share one display sort until the loaded rows change. */
    @Test
    fun displayOrderIsReusedUntilLoadedRowsChange() =
        runBlocking {
            val first = disappearingRow(FIRST, timelineAt = 200uL, expiresAt = LIVE_EXPIRES_AT)
            val second = disappearingRow(SECOND, timelineAt = 100uL, expiresAt = LIVE_EXPIRES_AT)
            val appState = standaloneAppState()
            val controller = buildController(appState, readCursorRow(lastReadId = SECOND, lastReadAt = 100uL))
            try {
                controller.applyTimelinePage(timelinePage(first, second), replaceWindow = true, updatePagination = true)
                val ordered = controller.loadedTimelineMessagesInDisplayOrder()
                assertSame(ordered, controller.loadedTimelineMessagesInDisplayOrder())

                val optimisticId = "optimistic-$THIRD"
                appState.optimisticMessages(controller.boundAccountRef, GROUP_ID)[optimisticId] =
                    TimelineMessage(
                        id = optimisticId,
                        record = TimelineProjector.toAppMessageRecord(disappearingRow(THIRD, 300uL, LIVE_EXPIRES_AT)),
                        status = MessageStatus.Sent,
                        projected = null,
                        timelineOrder = 1uL,
                        retentionAtSendSeconds = null,
                    )

                val reordered = controller.loadedTimelineMessagesInDisplayOrder()
                assertNotSame(ordered, reordered)
                assertEquals(3, reordered.size)
            } finally {
                controller.onCleared()
            }
        }

    /** Proves an optimistic anchor drops its projected copy before ordering for [optimisticItemId]. */
    private suspend fun assertOptimisticAnchorDefersExpiry(optimisticItemId: String) {
        val first = disappearingRow(FIRST, timelineAt = 200uL, expiresAt = PAST_EXPIRES_AT)
        val second = disappearingRow(SECOND, timelineAt = 100uL, expiresAt = LIVE_EXPIRES_AT)
        val appState = standaloneAppState()
        val controller = buildController(appState, readCursorRow(lastReadId = SECOND, lastReadAt = 100uL))
        try {
            controller.applyTimelinePage(
                timelinePage(first, second),
                replaceWindow = true,
                updatePagination = true,
            )
            // Authoritative page order puts first before second, so first is initially read and expired.
            assertFalse(checkNotNull(controller.retainedExpiryRow(FIRST)).second.deferSendTimeExpiry)
            assertTrue(controller.isRetainedRowGone(FIRST))

            val optimistic = appState.optimisticMessages(controller.boundAccountRef, GROUP_ID)
            val duplicate =
                TimelineMessage(
                    id = optimisticItemId,
                    record = TimelineProjector.toAppMessageRecord(second).copy(recordedAt = 150uL),
                    status = MessageStatus.Sent,
                    projected = null,
                    timelineOrder = 1uL,
                    retentionAtSendSeconds = RETENTION_SECONDS,
                )
            optimistic[optimisticItemId] = duplicate

            val ordered = controller.loadedTimelineMessagesInDisplayOrder()

            assertEquals(listOf(SECOND, FIRST), ordered.map { it.record.messageIdHex })
            assertEquals(optimisticItemId, ordered[0].id)
            assertNull("optimistic duplicate must keep raw-id precedence", ordered[0].projected)
            assertTrue(checkNotNull(controller.retainedExpiryRow(FIRST)).second.deferSendTimeExpiry)
            assertFalse(controller.isRetainedRowGone(FIRST))
            assertEquals(1, controller.foregroundSweepExpiryRows().count { it.deferSendTimeExpiry })
        } finally {
            controller.onCleared()
        }
    }

    /** Builds a received disappearing record with pinned retention duration and wall-clock expiry. */
    private fun disappearingRow(
        messageId: String,
        timelineAt: ULong,
        expiresAt: ULong,
        plaintext: String = "body-$messageId",
    ): TimelineMessageRecordFfi =
        timelineRecord(messageId = messageId, timelineAt = timelineAt, plaintext = plaintext).copy(
            retentionSeconds = RETENTION_SECONDS,
            retentionExpiresAt = expiresAt,
        )

    /** Builds a native chat-list row seeded with the requested persisted read cursor. */
    private fun readCursorRow(
        lastReadId: String?,
        lastReadAt: ULong?,
    ): ChatListRowFfi =
        notificationChatListRow().copy(
            lastReadMessageIdHex = lastReadId,
            lastReadTimelineAt = lastReadAt,
            unreadCount = 2uL,
            hasUnread = true,
            manuallyMarkedUnread = false,
            firstUnreadMessageIdHex = THIRD,
        )

    /** Creates an isolated app state for synchronous page and live-change controller tests. */
    private fun standaloneAppState(): WhiteNoiseAppState {
        val scripted = ScriptedConversationLiveSubscriptions(emptyList(), conversationTimelineTestGroup())
        return conversationTimelineTestAppState(scripted.subscriptions)
    }

    /** Creates a native-backed fixture answering Markdown parses and mark-read calls. */
    private fun readFixture(
        row: ChatListRowFfi,
        onRead: (() -> ChatListRowFfi?)? = null,
    ): NotificationBootstrapTestFixture =
        NotificationBootstrapTestFixture(
            context = ApplicationProvider.getApplicationContext(),
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            chatListRows = listOf(row),
            chatGroups = listOf(conversationTimelineTestGroup()),
            emitStartupNotification = false,
            onMarkTimelineMessageRead = onRead,
        )

    /** Constructs a controller pinned to the fixed test clock and optional foreground read visibility. */
    private fun buildController(
        appState: WhiteNoiseAppState,
        readRow: ChatListRowFfi,
        startOnConstruction: Boolean = false,
        attachVisible: Boolean = false,
    ): ConversationController =
        ConversationController(
            appState = appState,
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            initialChatListRow = readRow,
            accountRefOverride = ACCOUNT_REF,
            startOnConstruction = startOnConstruction,
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            clockMillis = { NOW_MILLIS },
        ).also { controller ->
            if (attachVisible) {
                appState.attachConversationController(controller)
                appState.setAppInForeground(true, dismissRetainedVisibleConversation = false)
                appState.setActiveConversationFromUi(ACCOUNT_REF, GROUP_ID)
            }
        }

    private companion object {
        const val ACCOUNT_REF = ConversationTimelineTestIds.ACCOUNT_REF
        val GROUP_ID = ConversationTimelineTestIds.GROUP_ID
        val FIRST = "11".repeat(32)
        val SECOND = "22".repeat(32)
        val THIRD = "33".repeat(32)
        val FOURTH = "44".repeat(32)
        const val RETENTION_SECONDS = 60uL
        const val NOW_MILLIS = 10_000_000L
        const val NOW_SECONDS = 10_000uL
        const val PAST_EXPIRES_AT = 500uL
        const val LIVE_EXPIRES_AT = 20_000uL
    }
}
