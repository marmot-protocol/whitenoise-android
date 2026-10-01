package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
internal class ConversationVisibleReadRecoveryTest : ConversationVisibleReadTestSupport() {
    /** Verifies a retained controller cannot acknowledge attention after navigation, backgrounding or locking. */
    @Test
    fun hiddenRetainedControllerCannotConsumeNewManualReminder() =
        runBlocking {
            val reminder = reminderRow()
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder) { read }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                fixture.appState.clearActiveConversation()
                chats.applyChatListRow(reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertTrue(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
                fixture.appState.setActiveConversationFromUi(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    reminder.groupIdHex,
                )
                fixture.appState.setAppInForeground(false)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.setAppInForeground(true, dismissRetainedVisibleConversation = false)
                showAppLock(fixture.appState)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.markAppUnlockSucceeded(dismissRetainedVisibleConversation = false)
                fixture.appState.setActiveConversationFromUi("other-account", reminder.groupIdHex)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.setActiveConversationFromUi(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    reminder.groupIdHex,
                )
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies a null native result is acknowledged once without requiring a mounted list. */
    @Test
    fun nativeReminderWithoutBoundChatListIsConsumedOnceEvenWhenReadReturnsNull() =
        runBlocking {
            val reminder = reminderRow()
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder) { null }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, read)
                controller.markReadUpTo(MESSAGE_ID)
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                // A new reminder can arrive without observing the preceding
                // manual=false echo after a successful null command result.
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, reminder.copy())
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(3, fixture.markReadCalls.get())
                assertTrue(fixture.markReadRequests.all { it.third == MESSAGE_ID })
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies initial and retry attempts wait until the correct account and group regain visible ownership. */
    @Test
    fun firstAndRetriedReadsWaitForVisibleConversation() =
        runBlocking {
            val reminder = reminderRow()
            val calls = AtomicInteger()
            val fixture =
                fixture(reminder) {
                    if (calls.incrementAndGet() == 1) error("first visible read failed")
                    reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
                }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                fixture.appState.clearActiveConversation()
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(0, fixture.markReadCalls.get())
                activateConversation(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.clearActiveConversation()
                chats.applyChatListRow(reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertTrue(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
                activateConversation(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                assertFalse(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies a failed latest attempt restores the confirmed cursor without a mounted list. */
    @Test
    fun failedReadWithoutBoundChatListRestoresMostRecentlyConfirmedWatermark() =
        runBlocking {
            val newerId = "cc".repeat(32)
            val row = overlappingUnreadRow(newerId)
            val read =
                row.copy(
                    lastReadMessageIdHex = MESSAGE_ID,
                    lastReadTimelineAt = 2uL,
                    unreadCount = 1uL,
                    firstUnreadMessageIdHex = newerId,
                )
            val calls = AtomicInteger()
            val fixture =
                fixture(row) {
                    when (calls.incrementAndGet()) {
                        1 -> read
                        2 -> error("later read failed")
                        else ->
                            read.copy(
                                lastReadMessageIdHex = newerId,
                                lastReadTimelineAt = 3uL,
                                unreadCount = 0uL,
                                hasUnread = false,
                                firstUnreadMessageIdHex = null,
                            )
                    }
                }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, row)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(MESSAGE_ID, controller.latestChatListRow!!.lastReadMessageIdHex)
                controller.markReadUpTo(newerId)
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
                assertEquals(1uL, controller.latestChatListRow!!.unreadCount)
                controller.markReadUpTo(newerId)
                assertEquals(newerId, controller.lastReadMessageId)
                assertEquals(listOf(MESSAGE_ID, newerId, newerId), fixture.markReadRequests.map { it.third })
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies a visit-level acknowledgement preserves existing expiry while a genuinely new read anchors it. */
    @Test
    fun reopeningAtSavedReadWatermarkDoesNotExtendNativeDisappearingDeadline() =
        runBlocking {
            var nowMillis = 90_000L
            val reminder = reminderRow()
            val fixture = fixture(reminder) { reminder.copy(manuallyMarkedUnread = false, hasUnread = false) }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, reminder, clockMillis = { nowMillis })
                val record =
                    timelineRecord(MESSAGE_ID, timelineAt = 2uL).copy(
                        direction = "received",
                        retentionSeconds = 60uL,
                        retentionExpiresAt = 100uL,
                    )
                val page = TimelinePageFfi(messages = listOf(record), hasMoreBefore = false, hasMoreAfter = false)
                controller.applyTimelinePage(page, replaceWindow = true, updatePagination = true)
                assertEquals(listOf(MESSAGE_ID), timelineMessageIds(controller))

                nowMillis = 95_000L
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertFalse(controller.latestChatListRow!!.manuallyMarkedUnread)

                nowMillis = 105_000L
                controller.applyTimelinePage(page, replaceWindow = true, updatePagination = true)
                assertTrue("the native deadline must still hide the message", timelineMessageIds(controller).isEmpty())

                // Control: the same native record must actually support local
                // anchoring for a first read, while its native echo is pending.
                nowMillis = 90_000L
                val firstRead =
                    reminder.copy(
                        lastReadMessageIdHex = ConversationTimelineTestIds.MESSAGE_A,
                        lastReadTimelineAt = 1uL,
                    )
                val fresh = controller(fixture.appState, firstRead, clockMillis = { nowMillis })
                fresh.applyTimelinePage(page, replaceWindow = true, updatePagination = true)
                nowMillis = 95_000L
                fresh.markReadUpTo(MESSAGE_ID)
                nowMillis = 105_000L
                fresh.applyTimelinePage(page, replaceWindow = true, updatePagination = true)
                assertEquals(
                    "a genuinely new read still anchors retention",
                    listOf(MESSAGE_ID),
                    timelineMessageIds(fresh),
                )
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies a later failure preserves an earlier null-result acknowledgement until a native row supersedes it. */
    @Test
    fun failedReadPreservesEarlierSuccessfulAcknowledgementWhenNativeReturnedNoRow() =
        runBlocking {
            val newerId = "cc".repeat(32)
            val row = overlappingUnreadRow(newerId)
            val calls = AtomicInteger()
            val fixture =
                fixture(row) {
                    if (calls.incrementAndGet() in listOf(2, 4)) error("later read failed")
                    null
                }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, row)
                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(newerId)
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
                controller.markReadUpTo(newerId)
                assertEquals(newerId, controller.lastReadMessageId)
                assertEquals(listOf(MESSAGE_ID, newerId, newerId), fixture.markReadRequests.map { it.third })

                // A later native row replaces the null-result fallback.
                val confirmedId = "dd".repeat(32)
                val confirmed = row.copy(lastReadMessageIdHex = confirmedId, lastReadTimelineAt = 4uL)
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, confirmed)
                controller.markReadUpTo("ee".repeat(32))
                assertEquals(confirmedId, controller.lastReadMessageId)
            } finally {
                closeFixture(fixture)
            }
        }

    /** Verifies an older success arriving after a newer failure still restores the confirmed display cursor. */
    @Test
    fun olderSuccessfulReadAfterNewerFailureRestoresItsConfirmedDisplayWatermark() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val newerId = "cc".repeat(32)
            val row = overlappingUnreadRow(newerId)
            val fixture =
                fixture(row) {
                    if (calls.incrementAndGet() == 1) {
                        started.complete(Unit)
                        check(release.await(15, TimeUnit.SECONDS))
                        row.copy(lastReadMessageIdHex = MESSAGE_ID, lastReadTimelineAt = 2uL)
                    } else {
                        error("newer read rejected")
                    }
                }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, row)
                val older = async { controller.markReadUpTo(MESSAGE_ID) }
                started.await()
                controller.markReadUpTo(newerId)
                assertEquals(ConversationTimelineTestIds.MESSAGE_A, controller.lastReadMessageId)
                release.countDown()
                older.await()
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
                assertEquals(listOf(MESSAGE_ID, newerId), fixture.markReadRequests.map { it.third })
            } finally {
                release.countDown()
                closeFixture(fixture)
            }
        }
}
