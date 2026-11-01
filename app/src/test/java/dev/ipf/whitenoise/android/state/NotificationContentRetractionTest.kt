package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardPostedRegistry
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-path coverage for retracting deleted or hidden content (#3323) and withholding a read message's
 * delayed notification (#2065), driven through the real listener, presenter and card synchronizer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // Every scenario drives the same process-wide listener, presenter and card fixture.
class NotificationContentRetractionTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context: Application = RuntimeEnvironment.getApplication()
    private val cardKey = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)

    /** Grants posting and starts from an empty tray. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager().cancelAll()
    }

    /** Releases synchronizer hooks and clears the tray. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager().cancelAll()
    }

    /** A message that is already the conversation's read marker never posts its delayed first card. */
    @Test
    fun readCandidateNeverPostsItsFirstCard() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ -> readRow(marker = MESSAGE_ID) },
                )
            try {
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()
                val sawCard = pumpFor(NO_POST_WINDOW_MS)

                assertTrue("the gate must read MDK's read state", fixture.chatListRowCalls.get() > 0)
                assertFalse("a read message must never be written, even briefly", sawCard)
            } finally {
                fixture.close()
            }
        }

    /** A newer message after the marker still posts, as does one whose own account has read nothing. */
    @Test
    fun newerUnreadCandidateStillPosts() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    // Another account read this message; that never suppresses this account's card.
                    onChatListRow = { account, _ ->
                        if (account == ACCOUNT_REF) {
                            readRow(marker = OLDER_ID, unread = 1uL, firstUnread = MESSAGE_ID)
                        } else {
                            readRow(marker = MESSAGE_ID)
                        }
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
            } finally {
                fixture.close()
            }
        }

    /** Unreadable read state withholds the stale candidate instead of alerting. */
    @Test
    fun unreadableReadStateFailsClosed() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ -> throw IllegalStateException("runtime unavailable") },
                )
            try {
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()
                val sawCard = pumpFor(NO_POST_WINDOW_MS)

                assertTrue(fixture.chatListRowCalls.get() > 0)
                assertFalse(sawCard)
            } finally {
                fixture.close()
            }
        }

    /** A read that commits after the first post cancels the card before detached enrichment can rewrite it. */
    @Test
    fun readAfterFirstPostCancelsTheCardBeforeEnrichment() =
        runBlocking {
            val readCommitted = CountDownLatch(1)
            val calls = AtomicInteger(0)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ ->
                        if (calls.incrementAndGet() == 1) {
                            null
                        } else {
                            check(readCommitted.await(5, TimeUnit.SECONDS))
                            readRow(marker = MESSAGE_ID)
                        }
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                readCommitted.countDown()

                awaitCardGone()
            } finally {
                readCommitted.countDown()
                fixture.close()
            }
        }

    /** An accepted deletion retracts the card silently, a replay stays fenced, and a newer message still posts. */
    @Test
    fun acceptedDeletionRetractsTheCardAndFencesReplays() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                fixture.awaitRuntimeEventSubscription()

                fixture.emitRuntimeEvent(deletion(MESSAGE_ID))
                awaitCardGone()

                fixture.emitUpdate(fixture.update)
                assertFalse("a replayed candidate must stay fenced", pumpFor(NO_POST_WINDOW_MS))

                fixture.emitUpdate(
                    fixture.update.copy(messageIdHex = NEWER_ID, notificationKey = "startup:account-a:$NEWER_ID"),
                )
                fixture.awaitNotificationPosted()
            } finally {
                fixture.close()
            }
        }

    /** Deleting an older message through MDK's event stream keeps the newer sibling on the card (#3323). */
    @Test
    fun acceptedDeletionOfAnOlderEntryKeepsTheNewerSibling() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = OLDER_ID,
                    previewText = "older deleted text",
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                fixture.awaitRuntimeEventSubscription()
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = MESSAGE_ID,
                        notificationKey = "startup:account-a:$MESSAGE_ID",
                        previewText = "newer valid text",
                    ),
                )
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (activeCardText()?.contains("newer valid text") != true) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }

                fixture.emitRuntimeEvent(deletion(OLDER_ID))
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (activeCardText()?.contains("older deleted text") != false) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertTrue(activeCardText().orEmpty().contains("newer valid text"))
            } finally {
                fixture.close()
            }
        }

    /** Remote deletion cannot return through an older Android tray snapshot when the next message arrives. */
    @Test
    fun remoteDeletionStaysRetractedWhenThePlatformSnapshotLags() = staleSnapshotAfterRemoval(localHide = false)

    /** Local hide uses the same production retraction and retained-history boundary as remote deletion. */
    @Test
    fun localHideStaysRetractedWhenThePlatformSnapshotLags() = staleSnapshotAfterRemoval(localHide = true)

    /** Drives the real event/hide route, then makes Android expose its old A/B payload for C's history read. */
    private fun staleSnapshotAfterRemoval(localHide: Boolean) =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = OLDER_ID,
                    previewText = "older deleted text",
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                fixture.awaitRuntimeEventSubscription()
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = MESSAGE_ID,
                        notificationKey = "startup:account-a:$MESSAGE_ID",
                        previewText = "newer valid text",
                    ),
                )
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (activeCardText()?.contains("newer valid text") != true) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                val old =
                    manager()
                        .activeNotifications
                        .single { it.tag == cardKey.tag && it.id == cardKey.id }
                        .notification
                removeOlderNotificationEntry(fixture, localHide)
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (activeCardText()?.contains("older deleted text") != false) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertTrue(activeCardText().orEmpty().contains("newer valid text"))
                // Only the platform observation moves backwards; the accepted-write registry remains untouched.
                manager().notify(cardKey.tag, cardKey.id, old)
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = NEWER_ID,
                        notificationKey = "startup:account-a:$NEWER_ID",
                        previewText = "fresh valid text",
                    ),
                )
                awaitShownMessage(NEWER_ID)
                val text = activeCardText().orEmpty()
                assertTrue(text.contains("newer valid text"))
                assertTrue(text.contains("fresh valid text"))
                assertFalse(text.contains("older deleted text"))
            } finally {
                fixture.close()
            }
        }

    /** Runs the same accepted hide or subscribed deletion event used by both stale-snapshot cases. */
    private suspend fun removeOlderNotificationEntry(
        fixture: NotificationBootstrapTestFixture,
        localHide: Boolean,
    ) {
        if (localHide) {
            val hidden =
                fixture.runOnMainLooperPumping {
                    fixture.appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, OLDER_ID)
                }
            assertTrue(hidden)
        } else {
            fixture.emitRuntimeEvent(deletion(OLDER_ID))
        }
    }

    /** Awaits C's own accepted card identity, rather than unrelated listener or enrichment activity. */
    private suspend fun awaitShownMessage(messageId: String) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (manager().activeNotifications.none {
                    it.tag == cardKey.tag &&
                        it.id == cardKey.id &&
                        it.notification.extras
                            .getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX) == messageId
                }
            ) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** A deletion that lands after the read-state check but before the first write leaves no card. */
    @Test
    fun deletionWhileTheFirstPostIsQueuedLeavesNoCard() =
        runBlocking {
            val checking = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    // Holds the final read-state check open, so the deletion lands after it judged the message unread.
                    onChatListRow = { _, _ ->
                        checking.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        null
                    },
                )
            try {
                fixture.bootstrap()
                awaitLatch(checking)
                fixture.appState.retractNotificationContent(
                    RemovedNotificationMessage(ACCOUNT_REF, GROUP_ID, MESSAGE_ID),
                )
                release.countDown()

                assertFalse(pumpFor(NO_POST_WINDOW_MS))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** The same queued first post with no deletion still writes, proving the scenario above reaches the write. */
    @Test
    fun queuedFirstPostWithoutDeletionStillWrites() =
        runBlocking {
            val checking = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ ->
                        checking.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        null
                    },
                )
            try {
                fixture.bootstrap()
                awaitLatch(checking)
                release.countDown()
                fixture.awaitNotificationPosted()
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** A successful local hide retracts the card and keeps the hidden message from posting again. */
    @Test
    fun localHideRetractsTheCard() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(context = context, groupIdHex = GROUP_ID, messageIdHex = MESSAGE_ID)
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()

                val hidden =
                    fixture.runOnMainLooperPumping {
                        fixture.appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, MESSAGE_ID)
                    }
                assertTrue(hidden)
                awaitCardGone()

                fixture.emitUpdate(fixture.update)
                assertFalse(pumpFor(NO_POST_WINDOW_MS))
            } finally {
                fixture.close()
            }
        }

    /** A card left by an earlier process is retracted once the event stream opens and MDK reports it deleted. */
    @Test
    fun restartReconciliationRetractsADeletedCard() =
        runBlocking {
            assertRestartReconciliationCancels(
                timeline = { TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL, deleted = true)), false, false) },
                row = { null },
            )
        }

    /** A card left by an earlier process whose message was read meanwhile is cancelled at reconciliation. */
    @Test
    fun restartReconciliationCancelsAReadCard() =
        runBlocking {
            assertRestartReconciliationCancels(
                timeline = { TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL)), false, false) },
                row = { readRow(marker = MESSAGE_ID) },
            )
        }

    /** Reopening the actual stream cannot multiply a blocked binding, and its latest request still removes the card. */
    @Test
    fun repeatedStreamOpensShareTheReadStateWorker() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = AtomicInteger(0)
            val read = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onChatListRow = { _, _ ->
                        val wasRead = read.get()
                        reads.incrementAndGet()
                        entered.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                        readRow(marker = MESSAGE_ID).takeIf { wasRead }
                    },
                    onTimelineMessages = { _, _ -> TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL)), false, false) },
                )
            try {
                assertTrue(LocalNotificationPresenter(context).show(fixture.update, shortNpub = { "sender" }))
                fixture.bootstrap()
                awaitLatch(entered)
                repeat(3) {
                    val opens = fixture.eventsSubscriptions.get()
                    // A real event resets reconnect backoff and shares the same reconciliation worker.
                    fixture.emitRuntimeEvent(readStateChanged())
                    fixture.endRuntimeEventStream()
                    withTimeout(10_000L) {
                        while (fixture.eventsSubscriptions.get() <= opens) {
                            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                            delay(1L)
                        }
                    }
                }
                assertTrue(
                    "the existing card stays visible while native state is unreadable",
                    pumpFor(NO_POST_WINDOW_MS),
                )
                assertEquals("reopens and read changes must share one unfinished binding", 1, reads.get())
                assertEquals("all assertions precede native release", 1L, release.count)
                read.set(true)
                release.countDown()
                awaitCardGone()
                assertEquals("the dirty worker must read again after the stale snapshot", 2, reads.get())
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /**
     * A read MDK reports while a card is shown removes it silently (#2065). The card was left by an earlier process,
     * so only the read-state change can reconcile it.
     */
    @Test
    fun readStateChangeRemovesAShownCard() =
        runBlocking {
            val read = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onChatListRow = { _, _ -> readRow(marker = MESSAGE_ID).takeIf { read.get() } },
                    onTimelineMessages = { _, _ -> TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL)), false, false) },
                )
            try {
                assertTrue(LocalNotificationPresenter(context).show(fixture.update, shortNpub = { "sender" }))
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()
                pumpFor(NO_POST_WINDOW_MS)
                assertTrue("an unread card survives the stream-open reconciliation", cardActive())

                read.set(true)
                fixture.emitRuntimeEvent(readStateChanged())
                awaitCardGone()
            } finally {
                fixture.close()
            }
        }

    /** A read that commits while the first write is in flight cancels the card once that write lands. */
    @Test
    fun readCommittingDuringTheCheckIsCancelledAfterTheWrite() =
        runBlocking {
            val checking = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger(0)
            val read = AtomicBoolean(false)
            val writes = AtomicInteger(0)
            ConversationCardPostSynchronizer.testHook = countWrites(writes)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ ->
                        if (calls.incrementAndGet() == 1) {
                            // The first check reads the pre-read state, then waits while the read commits.
                            checking.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            null
                        } else {
                            readRow(marker = MESSAGE_ID).takeIf { read.get() }
                        }
                    },
                    onTimelineMessages = { _, _ -> TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL)), false, false) },
                )
            try {
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()
                awaitLatch(checking)
                read.set(true)
                fixture.emitRuntimeEvent(readStateChanged())
                pumpFor(NO_POST_WINDOW_MS)
                release.countDown()
                // The first write lands, and its read re-check sees the read that committed while it was in flight.
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (calls.get() < 2 || writes.get() < 1) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertEquals("the first write landed before it was withdrawn", 1, writes.get())
                awaitCardGone()
                assertFalse("the stale card must not come back", pumpFor(NO_POST_WINDOW_MS))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Repeated delivery of a read message, and its delivery through push-wake catch-up, never alert. */
    @Test
    fun repeatedAndPushWakeDeliveryOfAReadMessageNeverPosts() =
        runBlocking {
            lateinit var fixture: NotificationBootstrapTestFixture
            fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onChatListRow = { _, _ -> readRow(marker = MESSAGE_ID) },
                    onCatchUpAccounts = { fixture.emitNotification() },
                )
            try {
                fixture.bootstrap()
                assertTrue(
                    "the encrypted wake marker must be admitted",
                    PushTokenStore.create(context).recordPendingPushWakeCatchUp(),
                )
                fixture.runWithMainLooperPumping { fixture.appState.runPushWakeRecoveryAttempt() }
                fixture.awaitUpdateConsumed()
                repeat(2) { fixture.emitUpdate(fixture.update) }

                assertFalse(pumpFor(NO_POST_WINDOW_MS * 2))
                assertTrue(fixture.chatListRowCalls.get() >= 3)
            } finally {
                fixture.close()
            }
        }

    /**
     * When MDK state stays unreadable, reconciliation retries and then keeps the shown card for a later pass instead
     * of wiping valid content it cannot verify yet (#3323).
     */
    @Test
    fun unavailableReconciliationStateKeepsTheCardAfterRetries() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onChatListRow = { _, _ -> throw IllegalStateException("runtime unavailable") },
                )
            try {
                assertTrue(LocalNotificationPresenter(context).show(fixture.update, shortNpub = { "sender" }))
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (fixture.chatListRowCalls.get() < 3) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                pumpFor(NO_POST_WINDOW_MS)

                assertTrue("an unverifiable but valid card stays shown", cardActive())
            } finally {
                fixture.close()
            }
        }

    /** A deleted older entry beyond the candidate page is dropped at restart while the newer sibling stays. */
    @Test
    fun restartDropsADeletedOlderEntryAndKeepsTheNewerSibling() =
        runBlocking {
            val olderId = OLDER_ID
            val filler = (1..60).map { record("%064x".format(it), 100uL + it.toULong()) }
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onTimelineMessages = { _, _ ->
                        val rows = listOf(record(olderId, 10uL, deleted = true)) + filler + record(MESSAGE_ID, 200uL)
                        TimelinePageFfi(rows, false, false)
                    },
                )
            try {
                val presenter = LocalNotificationPresenter(context)
                val older = fixture.update.copy(messageIdHex = olderId, previewText = "older deleted text")
                assertTrue(presenter.show(older, shortNpub = { "sender" }))
                assertTrue(presenter.show(fixture.update.copy(previewText = "newer valid text"), shortNpub = { "s" }))
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()

                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (activeCardText()?.contains("older deleted text") != false) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertTrue(cardActive())
                assertTrue(activeCardText().orEmpty().contains("newer valid text"))
            } finally {
                fixture.close()
            }
        }

    /** A post survives the captured older snapshot and subsequent reads that include its current projection. */
    @Test
    fun aPostLandingDuringTheReconciliationSnapshotStays() =
        runBlocking {
            val snapshotReading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pageReads = AtomicInteger(0)
            val olderShown = AtomicBoolean(false)
            val newerProjected = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = OLDER_ID,
                    emitStartupNotification = false,
                    onTimelineMessages = { _, _ ->
                        val page =
                            if (newerProjected.get()) {
                                listOf(record(OLDER_ID, 10uL), record(MESSAGE_ID, 20uL))
                            } else {
                                listOf(record(OLDER_ID, 10uL))
                            }
                        if (olderShown.get() && pageReads.incrementAndGet() == 1) {
                            snapshotReading.countDown()
                            check(release.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        }
                        TimelinePageFfi(page, false, false)
                    },
                )
            try {
                val older = fixture.update.copy(previewText = "older valid text")
                assertTrue(LocalNotificationPresenter(context).show(older, shortNpub = { "sender" }))
                olderShown.set(true)
                fixture.bootstrap()
                awaitLatch(snapshotReading)
                newerProjected.set(true)
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = MESSAGE_ID,
                        notificationKey = "startup:account-a:$MESSAGE_ID",
                        previewText = "newer posted during snapshot",
                    ),
                )
                awaitCardContaining("newer posted during snapshot")
                val reconciled = observeNextCardReconciliation()
                release.countDown()
                awaitLatch(reconciled)
                assertTrue(activeCardText().orEmpty().contains("newer posted during snapshot"))
                assertTrue(activeCardText().orEmpty().contains("older valid text"))
                pumpFor(NO_POST_WINDOW_MS)

                assertTrue(activeCardText().orEmpty().contains("newer posted during snapshot"))
                assertTrue(activeCardText().orEmpty().contains("older valid text"))
            } finally {
                release.countDown()
                ConversationCardPostSynchronizer.testHook = null
                fixture.close()
            }
        }

    /** Waits until the card includes the required body before releasing the stale native snapshot. */
    private suspend fun awaitCardContaining(text: String) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (activeCardText()?.contains(text) != true) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Signals after the next reconciliation finishes mutating this fixture's message card. */
    private fun observeNextCardReconciliation(): CountDownLatch {
        val reconciled = CountDownLatch(1)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                /** Ignores writes and other cards so only the targeted reconciliation completes the wait. */
                override fun onLockReleased(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.DISMISS_CANCEL &&
                        notificationTag == cardKey.tag &&
                        notificationId == cardKey.id
                    ) {
                        reconciled.countDown()
                    }
                }
            }
        return reconciled
    }

    /** A candidate MDK already reports deleted never posts, even before anything in its conversation was read. */
    @Test
    fun aDeletedCandidateWithoutAReadMarkerNeverPosts() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onTimelineMessages = { _, _ ->
                        TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL, deleted = true)), false, false)
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()

                assertFalse(pumpFor(NO_POST_WINDOW_MS))
            } finally {
                fixture.close()
            }
        }

    /** A candidate MDK has not projected yet still alerts, even when the conversation shows nothing unread (#2065). */
    @Test
    fun anUnprojectedCandidateStillPosts() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ -> readRow(marker = OLDER_ID) },
                    onTimelineMessages = { _, _ -> TimelinePageFfi(listOf(record(OLDER_ID, 12uL)), false, false) },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
            } finally {
                fixture.close()
            }
        }

    /** A slow reconciliation in one conversation never delays a deletion in another (#3323). */
    @Test
    fun aSlowReconciliationNeverStallsAnotherDeletion() =
        runBlocking {
            val slowGroup = "ef56ab78"
            val release = CountDownLatch(1)
            val slowReading = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                    onTimelineMessages = { _, group ->
                        if (group == slowGroup) {
                            slowReading.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        TimelinePageFfi(listOf(record(MESSAGE_ID, 12uL)), false, false)
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()
                val presenter = LocalNotificationPresenter(context)
                assertTrue(presenter.show(fixture.update.copy(groupIdHex = slowGroup), shortNpub = { "sender" }))
                assertTrue(presenter.show(fixture.update, shortNpub = { "sender" }))
                fixture.emitRuntimeEvent(readStateChanged(slowGroup))
                awaitLatch(slowReading)

                fixture.emitRuntimeEvent(deletion(MESSAGE_ID))
                awaitCardGone()
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** A reaction never quotes into the shade from a conversation holding a message hidden on this device (#3323). */
    @Test
    fun aReactionQuoteIsWithheldWhenItsConversationHasAHiddenMessage() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                )
            val reactionKey = LocalNotificationFormatter.reactionDismissalKey(ACCOUNT_REF, GROUP_ID)
            try {
                fixture.bootstrap()
                val hidden =
                    fixture.runOnMainLooperPumping {
                        fixture.appState.hideMessageForMe(ACCOUNT_REF, GROUP_ID, OLDER_ID)
                    }
                assertTrue(hidden)
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = "ef".repeat(32),
                        notificationKey = "reaction:account-a",
                        reactionEmoji = "👍",
                        reactedToPreview = "hidden quoted secret",
                    ),
                )
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (manager().activeNotifications.none { it.tag == reactionKey.tag }) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                val reaction =
                    manager().activeNotifications.first { it.tag == reactionKey.tag && it.id == reactionKey.id }
                assertFalse(
                    reaction.notification.extras
                        .toString()
                        .contains("hidden quoted secret"),
                )
            } finally {
                fixture.close()
            }
        }

    /** A historical local hide alone does not suppress an unrelated new quote in a fresh app-state owner. */
    @Test
    fun aPersistedHideDoesNotPermanentlyDisableReactionQuotes() =
        runBlocking {
            val preferences = context.getSharedPreferences("whitenoise", 0)
            val key = requireNotNull(MessageHidePreferences.preferenceKey(ACCOUNT_REF, GROUP_ID))
            val previous = MessageHidePreferences.readHiddenMessageIdsByKey(preferences, key)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    emitStartupNotification = false,
                )
            try {
                assertTrue(MessageHidePreferences.writeHiddenMessageIdsByKey(preferences, key, setOf(OLDER_ID)))
                fixture.bootstrap()
                assertTrue(OLDER_ID in fixture.appState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID))
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = "ef".repeat(32),
                        notificationKey = "reaction:account-a",
                        reactionEmoji = "👍",
                        reactedToPreview = "valid new quote",
                    ),
                )
                val reactionKey = LocalNotificationFormatter.reactionDismissalKey(ACCOUNT_REF, GROUP_ID)
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (
                        manager().activeNotifications.none { it.tag == reactionKey.tag && it.id == reactionKey.id }
                    ) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                val card =
                    manager()
                        .activeNotifications
                        .first { it.tag == reactionKey.tag && it.id == reactionKey.id }
                val extrasText = card.notification.extras.toString()
                assertTrue(extrasText.contains("valid new quote"))
            } finally {
                fixture.close()
                assertTrue(MessageHidePreferences.writeHiddenMessageIdsByKey(preferences, key, previous))
            }
        }

    /** A second same-chat message waits for a healthy 200 ms enrichment read and reaches the actual tray. */
    @Test
    fun aSecondMessagePostsWhileTheFirstEnrichmentReadIsInFlight() =
        runBlocking {
            val enrichmentEntered = CountDownLatch(1)
            val releaseEnrichment = CountDownLatch(1)
            val secondPrepared = CountDownLatch(1)
            val outcomes = CopyOnWriteArrayList<String>()
            val fixture = delayedEnrichmentFixture(enrichmentEntered, releaseEnrichment, secondPrepared, outcomes)
            var releaseJob: Job? = null
            try {
                fixture.bootstrap()
                fixture.emitUpdate(fixture.update)
                fixture.awaitNotificationPosted()
                awaitLatch(enrichmentEntered)
                releaseJob =
                    launch(Dispatchers.IO) {
                        check(secondPrepared.await(5, TimeUnit.SECONDS))
                        delay(200)
                        releaseEnrichment.countDown()
                    }
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = NEWER_ID,
                        notificationKey = "startup:account-a:$NEWER_ID",
                        previewText = "Second message during enrichment",
                    ),
                )
                withTimeout(AWAIT_TIMEOUT_MS) {
                    while (outcomes.size < 2) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertEquals("healthy busy admission must remain eligible", "unread", outcomes[1])
                awaitCardMessageId(NEWER_ID)
                releaseJob.join()
            } finally {
                secondPrepared.countDown()
                releaseEnrichment.countDown()
                releaseJob?.cancelAndJoin()
                fixture.close()
            }
        }

    /** The listener's first row read posts, its enrichment read pauses, and the second first-post read waits. */
    private fun delayedEnrichmentFixture(
        entered: CountDownLatch,
        release: CountDownLatch,
        secondPrepared: CountDownLatch,
        outcomes: MutableList<String>,
    ): NotificationBootstrapTestFixture {
        val reads = AtomicInteger()
        val prepared = AtomicInteger()
        return NotificationBootstrapTestFixture(
            context = context,
            groupIdHex = GROUP_ID,
            messageIdHex = MESSAGE_ID,
            emitStartupNotification = false,
            onChatListRow = { _, _ ->
                if (reads.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                null
            },
            notificationFirstPostTimingObserver = { event ->
                val secondContent =
                    event.stage == NotificationFirstPostTimingStage.ContentComplete && prepared.incrementAndGet() == 2
                if (secondContent) secondPrepared.countDown()
                if (event.stage == NotificationFirstPostTimingStage.CandidateGateComplete) outcomes += event.outcome
            },
        )
    }

    /** A tag match alone could be the first card; require the actual newer message stamped in the platform payload. */
    private suspend fun awaitCardMessageId(messageId: String) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (
                manager().activeNotifications.none {
                    it.tag == cardKey.tag &&
                        it.id == cardKey.id &&
                        it.notification.extras.getString(
                            LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
                        ) == messageId
                }
            ) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Slow ordinary relay posts reach WNPerf without a timing observer or push-recovery trace installed. */
    @Test
    fun aNotificationCandidateGateEmitsRealPrivacySafePerformanceEvidence() =
        runBlocking {
            PerformanceDiagnostics.stop()
            assumeTrue(PerformanceDiagnostics.start().available)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    previewText = "Private notification candidate",
                    onChatListRow = { _, _ ->
                        ShadowSystemClock.advanceBy(Duration.ofMillis(150L))
                        null
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                val line =
                    PerformanceDiagnostics.exportLines().single {
                        "op=notification_post phase=notification_candidate_gate" in it
                    }
                assertTrue(line.contains("result=success layer=android"))
                assertFalse(line.contains(ACCOUNT_REF))
                assertFalse(line.contains(GROUP_ID))
                assertFalse(line.contains(MESSAGE_ID))
                assertFalse(line.contains("Private notification candidate"))
            } finally {
                fixture.close()
                PerformanceDiagnostics.stop()
            }
        }

    /** A synchronous binding remains blocked after the gate has returned its two bounded awaits. */
    @Test
    fun aStalledReadStateCheckIsBounded() = assertStalledReadBound(checkNextConversation = false)

    /** The real serial listener posts another chat's message while the first native read is still blocked. */
    @Test
    fun aSecondConversationPostsWhileTheFirstReadIsStalled() = assertStalledReadBound(checkNextConversation = true)

    /** Holds the binding until all deadline and platform assertions finish, then always releases it for cleanup. */
    private fun assertStalledReadBound(checkNextConversation: Boolean) =
        runBlocking {
            val entered = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val completedAtNanos = AtomicLong(0L)
            val release = CountDownLatch(1)
            val trace = StalledReadTrace()
            val nextGroup = "a1b2c3d4"
            val fixture = stalledReadFixture(entered, release, completed, completedAtNanos, trace)
            var startedUptimeMillis = 0L
            var started = 0L
            try {
                fixture.bootstrap()
                startedUptimeMillis = SystemClock.uptimeMillis()
                started = System.nanoTime()
                trace.record("emit_first")
                fixture.emitUpdate(fixture.update)
                awaitStalledReadLatch(entered, startedUptimeMillis, started)
                trace.record("entered_observed")
                if (checkNextConversation) {
                    trace.record("emit_second")
                    fixture.emitUpdate(fixture.update.copy(groupIdHex = nextGroup))
                }
                withTimeout(4_500L) { awaitStalledReadLatch(completed, startedUptimeMillis, started) }
                val elapsed = (completedAtNanos.get() - started) / 1_000_000
                assertTrue("the first gate must finish near its 3s budget, actual=$elapsed", elapsed < 4_500L)
                assertFalse(manager().activeNotifications.any { it.tag == cardKey.tag && it.id == cardKey.id })
                if (checkNextConversation) {
                    val nextKey = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, nextGroup)
                    withTimeout(2_000L) {
                        while (manager().activeNotifications.none { it.tag == nextKey.tag && it.id == nextKey.id }) {
                            pumpStalledReadMain(startedUptimeMillis, started)
                            delay(1L)
                        }
                    }
                }
                assertEquals("assertions run before releasing the binding", 1L, release.count)
            } catch (failure: Throwable) {
                trace.reportFailure(failure) {
                    val active = manager().activeNotifications
                    val nextKey = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, nextGroup)
                    "start_ns=$started start_uptime_ms=$startedUptimeMillis completion_ns=${completedAtNanos.get()} " +
                        "latches=${entered.count}/${completed.count}/${release.count} " +
                        "first_card=${active.any { it.tag == cardKey.tag && it.id == cardKey.id }} " +
                        "second_card=${active.any { it.tag == nextKey.tag && it.id == nextKey.id }}"
                }
                throw failure
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Keeps the real blocking native read and production timing observer while retaining bounded failure evidence. */
    private fun stalledReadFixture(
        entered: CountDownLatch,
        release: CountDownLatch,
        completed: CountDownLatch,
        completedAtNanos: AtomicLong,
        trace: StalledReadTrace,
    ) = NotificationBootstrapTestFixture(
        context = context,
        groupIdHex = GROUP_ID,
        messageIdHex = MESSAGE_ID,
        emitStartupNotification = false,
        onChatListRow = { _, group ->
            val label = if (group == GROUP_ID) "first" else "second"
            trace.record("native_${label}_enter")
            try {
                if (group == GROUP_ID) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                null
            } catch (failure: Throwable) {
                trace.record("native_${label}_error_${failure.javaClass.simpleName}")
                throw failure
            } finally {
                trace.record("native_${label}_exit")
            }
        },
        notificationFirstPostTimingObserver = { event ->
            val observedAtNanos = System.nanoTime()
            if (event.stage == NotificationFirstPostTimingStage.CandidateGateComplete) {
                completedAtNanos.compareAndSet(0L, observedAtNanos)
                completed.countDown()
            }
            trace.record("stage_${event.stage}_${event.outcome}")
        },
    )

    /** Android Handler deadlines advance with real elapsed time rather than one virtual millisecond per poll. */
    private fun pumpStalledReadMain(
        startedUptimeMillis: Long,
        startedNanos: Long,
    ) {
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        val remainingMillis = (startedUptimeMillis + elapsedMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(remainingMillis))
    }

    /** The stall checks share one clock origin; the general race-checkpoint helper remains unchanged. */
    private suspend fun awaitStalledReadLatch(
        latch: CountDownLatch,
        startedUptimeMillis: Long,
        startedNanos: Long,
    ) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (latch.count > 0L) {
                pumpStalledReadMain(startedUptimeMillis, startedNanos)
                delay(1L)
            }
        }
    }

    /** Fixed-size synthetic stage labels retain both clock domains without changing notification decisions. */
    private class StalledReadTrace {
        private val sequence = AtomicInteger()
        private val events = CopyOnWriteArrayList<String>()

        fun record(label: String) {
            runCatching {
                val index = sequence.getAndIncrement()
                if (index < 32) {
                    events +=
                        "$index $label ns=${System.nanoTime()} uptime_ms=${SystemClock.uptimeMillis()} " +
                        "thread=${Thread.currentThread().name}"
                }
            }
        }

        fun reportFailure(
            failure: Throwable,
            detail: () -> String,
        ) {
            runCatching {
                record("failure_${failure.javaClass.simpleName}")
                val diagnostic = "stalled_read ${detail()}\n${snapshot()}"
                println(diagnostic)
                failure.addSuppressed(AssertionError(diagnostic))
            }
        }

        fun snapshot(): String =
            "main=${Dispatchers.Main} now_ns=${System.nanoTime()} uptime_ms=${SystemClock.uptimeMillis()}\n" +
                events.joinToString("\n")
    }

    /** One failed read-state read is retried within the budget, so a transient failure still alerts (#2065). */
    @Test
    fun aTransientReadStateFailureIsRetried() =
        runBlocking {
            val calls = AtomicInteger(0)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ ->
                        check(calls.incrementAndGet() > 1) { "transient failure" }
                        null
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
            } finally {
                fixture.close()
            }
        }

    /** An unreadable state after the first post never stops that card's enrichment (#2065). */
    @Test
    fun anUnreadableStateAfterThePostStillEnriches() =
        runBlocking {
            val calls = AtomicInteger(0)
            val releaseFirstRead = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    previewText = "already final",
                    onChatListRow = { _, _ ->
                        check(calls.incrementAndGet() == 1) { "runtime unavailable" }
                        null
                    },
                    // The first sender read times out, so the posted card needs a late correction.
                    onDisplayName = { _, _ ->
                        releaseFirstRead.await(5, TimeUnit.SECONDS)
                        "Alice"
                    },
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                releaseFirstRead.countDown()

                fixture.awaitSenderDisplayNameCalls(expected = 2)
                assertTrue(cardActive())
            } finally {
                releaseFirstRead.countDown()
                fixture.close()
            }
        }

    /** Pre-posts the fixture's card as an earlier process would, then starts a process that must cancel it. */
    private suspend fun assertRestartReconciliationCancels(
        timeline: () -> TimelinePageFfi,
        row: () -> ChatListRowFfi?,
    ) {
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                groupIdHex = GROUP_ID,
                messageIdHex = MESSAGE_ID,
                emitStartupNotification = false,
                onTimelineMessages = { _, _ -> timeline() },
                onChatListRow = { _, _ -> row() },
            )
        try {
            assertTrue(LocalNotificationPresenter(context).show(fixture.update, shortNpub = { "sender" }))
            assertTrue(cardActive())

            fixture.bootstrap()
            fixture.awaitRuntimeEventSubscription()
            awaitCardGone()
        } finally {
            fixture.close()
        }
    }

    /** An accepted-deletion projection update for [messageIdHex]. */
    private fun deletion(messageIdHex: String): MarmotEventFfi =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = ACCOUNT_REF,
                accountLabel = ACCOUNT_REF,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = GROUP_ID,
                        messages = emptyList(),
                        changes =
                            listOf(
                                TimelineMessageChangeFfi.Upsert(
                                    TimelineUpdateTriggerFfi.MESSAGE_DELETED,
                                    record(messageIdHex, 12uL, deleted = true),
                                ),
                            ),
                        chatListRow = null,
                        chatListTrigger = ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                    ),
            ),
        )

    /** Counts serialized first writes so a test can prove one landed. */
    private fun countWrites(writes: AtomicInteger) =
        object : ConversationCardTestHook {
            override fun onBarrier(
                op: ConversationCardOp,
                barrier: ConversationCardBarrier,
                notificationTag: String,
                notificationId: Int,
            ) {
                if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_WRITE) {
                    writes.incrementAndGet()
                }
            }
        }

    /** MDK's report that [groupIdHex]'s read state changed. */
    private fun readStateChanged(groupIdHex: String = GROUP_ID): MarmotEventFfi =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = ACCOUNT_REF,
                accountLabel = ACCOUNT_REF,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = groupIdHex,
                        messages = emptyList(),
                        changes = emptyList(),
                        chatListRow = readRow(marker = MESSAGE_ID),
                        chatListTrigger = ChatListUpdateTriggerFfi.UNREAD_CHANGED,
                    ),
            ),
        )

    /** Every text the fixture card carries, including its retained MessagingStyle history. */
    private fun activeCardText(): String? =
        manager()
            .activeNotifications
            .firstOrNull { it.tag == cardKey.tag && it.id == cardKey.id }
            ?.notification
            ?.let { notification ->
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                val texts = style?.messages.orEmpty().map { it.text.toString() }
                (texts + notification.extras.toString()).joinToString("|")
            }

    /** One timeline row at [timelineAt]. */
    private fun record(
        id: String,
        timelineAt: ULong,
        deleted: Boolean = false,
    ) = requireNotNull(projectedTimelineMessage(timelineAppMessage(id, timelineAt)).projected).copy(deleted = deleted)

    /** The fixture conversation's chat-list row with its read marker at [marker]. */
    private fun readRow(
        marker: String,
        unread: ULong = 0uL,
        firstUnread: String? = null,
    ): ChatListRowFfi =
        chatRow(GROUP_ID).copy(
            lastReadMessageIdHex = marker,
            lastReadTimelineAt = 12uL,
            unreadCount = unread,
            hasUnread = unread > 0uL,
            firstUnreadMessageIdHex = firstUnread,
        )

    /** The platform notification manager. */
    private fun manager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Whether the fixture conversation's message card is in the tray. */
    private fun cardActive() = manager().activeNotifications.any { it.tag == cardKey.tag && it.id == cardKey.id }

    /** Pumps the main looper until the fixture card leaves the tray. */
    private suspend fun awaitCardGone() {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (cardActive()) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Pumps the main looper while waiting for a deterministic race checkpoint. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (latch.count > 0L) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Lets queued work run for [millis] so an unwanted post would have landed, and reports whether one ever did. */
    private suspend fun pumpFor(millis: Long): Boolean {
        var sawCard = cardActive()
        repeat((millis / PUMP_STEP_MS).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PUMP_STEP_MS))
            delay(PUMP_STEP_MS)
            sawCard = sawCard || cardActive()
        }
        return sawCard
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val GROUP_ID = "ab12cd34"
        val MESSAGE_ID = "ab".repeat(32)
        val OLDER_ID = "aa".repeat(32)
        val NEWER_ID = "cd".repeat(32)
        const val NO_POST_WINDOW_MS = 500L
        const val PUMP_STEP_MS = 10L
        const val AWAIT_TIMEOUT_MS = 60_000L
    }
}
