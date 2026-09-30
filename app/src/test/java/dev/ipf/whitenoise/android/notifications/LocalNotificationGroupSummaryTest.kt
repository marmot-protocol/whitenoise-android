package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The production presenter/coordinator against the OS tray, with controlled settling and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationGroupSummaryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationGroupReconciler.shared(context).close()
        NotificationChannels.ensureChannels(context)
    }

    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
    }

    @Test
    fun multipleAccountsKeepSeparateActionableChildrenAndOnePrivateSilentSummary() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "one")
            fixture.send("account-b", "group-b", "two")
            fixture.send("account-a", "group-c", "mention", mention = true)
            settle()
            val children = manager.activeNotifications.filter { UserEventNotificationGroup.child(it) != null }
            assertEquals(3, children.size)
            assertEquals(3, children.map { it.tag to it.id }.toSet().size)
            children.forEach {
                assertEquals(UserEventNotificationGroup.KEY, it.notification.group)
                assertNotNull(it.notification.contentIntent)
                assertNotNull(it.notification.deleteIntent)
            }
            assertTrue(children.first { it.id == LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID }.notification.actions.isNotEmpty())
            assertEquals("0", children.first { it.id == LocalNotificationFormatter.MENTION_NOTIFICATION_ID }.notification.sortKey)
            val summary = requireNotNull(fixture.summary())
            assertEquals("3 notifications", summary.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(Notification.VISIBILITY_PRIVATE, summary.visibility)
            assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
            assertNull(summary.sound)
            assertTrue(summary.vibrate?.isEmpty() != false)
            val publicText = summary.publicVersion.extras.toString()
            assertFalse(publicText.contains("Alice") || publicText.contains("General") || publicText.contains("account-a"))
            assertEquals(3, fixture.childWrites)
        }

    @Test
    fun removingOneAccountPreservesTheOtherAndRemovingTheLastChildRemovesTheSummary() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "one")
            fixture.send("account-b", "group-b", "two")
            settle()
            val keyA = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
            fixture.presenter.cancel(keyA.tag, keyA.id)
            settle()
            assertEquals(1, manager.activeNotifications.count { UserEventNotificationGroup.child(it) != null })
            assertEquals("1 notification", requireNotNull(fixture.summary()).extras.getCharSequence(Notification.EXTRA_TEXT))
            val keyB = LocalNotificationFormatter.conversationDismissalKey("account-b", "group-b")
            fixture.presenter.cancel(keyB.tag, keyB.id)
            settle()
            assertTrue(manager.activeNotifications.isEmpty())
        }

    @Test
    fun restoringALegacyCardPreservesActionsAndMessageWhileExcludingServiceAndUpdateCards() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            val original = fixture.send("account-a", "group-a", "legacy-message")
            fixture.coordinator.close()
            val extras = android.os.Bundle(original.extras).apply {
                remove(UserEventNotificationGroup.EXTRA_GENERATION)
                remove(UserEventNotificationGroup.EXTRA_CHILD)
            }
            manager.notify("account-a|group-a", 0, NotificationCompat.Builder(context, original).setGroup(null).setExtras(extras).build())
            val unrelated = NotificationCompat.Builder(context, NotificationChannelSpec.APP_UPDATES.id)
                .setSmallIcon(dev.ipf.whitenoise.android.R.drawable.ic_stat_whitenoise).setContentTitle("synthetic service").setOngoing(true).build()
            manager.notify("connections", 0, unrelated)
            manager.notify("update", 77, unrelated)
            val restored = NotificationGroupReconciler(context, backgroundScope, fixture.pacer)
            restored.request()
            settle()
            val children = manager.activeNotifications.mapNotNull(UserEventNotificationGroup::child)
            assertEquals(1, children.size)
            val adopted = manager.activeNotifications.single { it.tag == "account-a|group-a" }.notification
            assertEquals("legacy-message", adopted.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
            assertEquals(original.actions.size, adopted.actions.size)
            assertEquals(original.contentIntent, adopted.contentIntent)
            assertEquals("1 notification", requireNotNull(fixture.summary()).extras.getCharSequence(Notification.EXTRA_TEXT))
            assertNull(manager.activeNotifications.single { it.tag == "connections" }.notification.group)
            assertNull(manager.activeNotifications.single { it.tag == "update" }.notification.group)
            restored.close()
        }

    @Test
    fun aFailedTrayReadKeepsTheExistingSummaryAndLaterReconciliationRecovers() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "one")
            settle()
            val before = requireNotNull(fixture.summary()).extras.getString(UserEventNotificationGroup.EXTRA_SUMMARY_STATE)
            fixture.failRead = true
            fixture.coordinator.request()
            settle()
            assertEquals(before, requireNotNull(fixture.summary()).extras.getString(UserEventNotificationGroup.EXTRA_SUMMARY_STATE))
            fixture.failRead = false
            fixture.presenter.cancel("account-a|group-a", LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
            settle()
            assertNull(fixture.summary())
        }

    @Test
    fun delayedPlatformVisibilityGetsSettlingReadsWithoutCreatingPhantomChildren() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.hiddenReads = 2
            fixture.send("account-a", "group-a", "one")
            advanceTimeBy(300)
            runCurrent()
            assertNull(fixture.summary())
            settle()
            assertNotNull(fixture.summary())
            assertEquals(1, fixture.childWrites)
        }

    @Test
    fun failedChildWritesNeverCreateASummaryAndSummaryFailuresNeverReplayChildren() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.failChild = true
            assertFalse(fixture.presenter.show(alertBudgetUpdate("failed", fixture.now), shortNpub = { it }))
            settle()
            assertNull(fixture.summary())
            fixture.failChild = false
            fixture.failSummary = true
            fixture.send("account-a", "group-a", "one")
            settle()
            assertEquals(1, fixture.childWrites)
            assertNull(fixture.summary())
            assertTrue(fixture.summaryAttempts in 1..10)
            fixture.failSummary = false
            fixture.coordinator.request()
            settle()
            assertNotNull(fixture.summary())
            assertEquals(1, fixture.childWrites)
        }

    @Test
    fun droppedSummaryCancellationIsConfirmedAndRetried() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "one")
            settle()
            fixture.dropSummaryCancels = 1
            fixture.presenter.cancel("account-a|group-a", LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
            settle()
            assertNull(fixture.summary())
            assertEquals(2, fixture.summaryCancelAttempts)
        }

    @Test
    fun staleChildDeleteIntentCannotClearANewerCardOnTheSameKey() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            val old = fixture.send("account-a", "group-a", "old")
            val intent = shadowOf(old.deleteIntent).savedIntent
            fixture.send("account-a", "group-a", "new")
            dismissNotificationGroupGenerations(context, requireNotNull(UserEventNotificationGroup.dismissalChildren(intent)), pacer = fixture.pacer, request = fixture.coordinator::request)
            settle()
            val live = manager.activeNotifications.single { UserEventNotificationGroup.child(it) != null }.notification
            assertEquals("new", live.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
            assertNotNull(fixture.summary())
        }

    @Test
    fun summaryDeleteIntentRemovesRepresentedGenerationsButKeepsLaterArrivalsAcrossAccounts() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "old-a")
            fixture.send("account-b", "group-b", "old-b")
            settle()
            val intent = shadowOf(requireNotNull(fixture.summary()).deleteIntent).savedIntent
            fixture.send("account-b", "group-b", "new-b")
            val children = requireNotNull(UserEventNotificationGroup.dismissalChildren(intent))
            val fence = NotificationGroupDismissalFence(requireNotNull(intent.getStringExtra(UserEventNotificationGroup.EXTRA_FENCE_SESSION)), intent.getLongExtra(UserEventNotificationGroup.EXTRA_FENCE_SEQUENCE, -1L))
            dismissNotificationGroupGenerations(context, children, fence, fixture.pacer, request = fixture.coordinator::request)
            fixture.coordinator.request()
            settle()
            val live = manager.activeNotifications.single { UserEventNotificationGroup.child(it) != null }.notification
            assertEquals("new-b", live.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
            assertEquals("1 notification", requireNotNull(fixture.summary()).extras.getCharSequence(Notification.EXTRA_TEXT))
            dismissNotificationGroupGenerations(context, children, fence, fixture.pacer, request = fixture.coordinator::request)
            assertEquals(1, manager.activeNotifications.count { UserEventNotificationGroup.child(it) != null })
        }

    @Test
    fun processRestorationReadsOsCardsAndRemovesAnOrphanSummaryWithoutReplayingMessages() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "one")
            settle()
            fixture.coordinator.close()
            manager.cancel("account-a|group-a", LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID)
            val restored = NotificationGroupReconciler(context, CoroutineScope(backgroundScope.coroutineContext), fixture.pacer)
            restored.request()
            settle()
            assertTrue(manager.activeNotifications.isEmpty())
            assertEquals(1, fixture.childWrites)
            restored.close()
        }

    @Test
    fun aNewMessageBetweenReplyReadAndRewriteKeepsItsContentsAndGeneration() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            val old = fixture.send("account-a", "group-a", "old")
            val key = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
            val replacement =
                NotificationCompat.Builder(context, old)
                    .addExtras(android.os.Bundle().apply { putString(UserEventNotificationGroup.EXTRA_GENERATION, "new-generation") })
                    .build()
            ConversationCardPostSynchronizer.testHook =
                object : ConversationCardTestHook {
                    override fun onBarrier(op: ConversationCardOp, barrier: ConversationCardBarrier, notificationTag: String, notificationId: Int) {
                        if (op == ConversationCardOp.MARK_REPLY_HANDLED && barrier == ConversationCardBarrier.AFTER_READ) manager.notify(key.tag, key.id, replacement)
                    }
                }
            assertFalse(fixture.presenter.markDirectReplyHandled(key.tag, key.id, "synthetic reply"))
            val live = manager.activeNotifications.single { it.tag == key.tag && it.id == key.id }.notification
            assertEquals("new-generation", live.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION))
            assertNull(live.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY))
        }

    @Test
    fun aRefusedPreparingPostDoesNotCancelANewerVisibleCard() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            val original = fixture.send("account-a", "group-a", "original")
            val represented = manager.activeNotifications.mapNotNull(UserEventNotificationGroup::child)
            val replacement = NotificationCompat.Builder(context, original)
                .addExtras(android.os.Bundle().apply {
                    putString(UserEventNotificationGroup.EXTRA_GENERATION, "later-generation")
                    putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, "later-visible")
                }).build()
            ConversationCardPostSynchronizer.testHook = object : ConversationCardTestHook {
                override fun onBarrier(op: ConversationCardOp, barrier: ConversationCardBarrier, notificationTag: String, notificationId: Int) {
                    if (barrier == ConversationCardBarrier.BEFORE_PLATFORM_WRITE) {
                        // Simulate the summary callback after the show precheck and a newer OS card.
                        NotificationCardGenerations.dismissThrough(NotificationCardGenerations.captureFence(), represented)
                        manager.notify(notificationTag, notificationId, replacement)
                    }
                }
            }
            fixture.now += 6_000
            assertFalse(fixture.presenter.show(alertBudgetUpdate("refused", fixture.now, false, "account-a", "group-a"), shortNpub = { it }))
            val live = manager.activeNotifications.single { it.tag == "account-a|group-a" }.notification
            assertEquals("later-generation", live.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION))
            assertEquals("later-visible", live.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
            assertEquals(1, fixture.childWrites)
        }

    private fun TestScope.settle() {
        advanceTimeBy(2_000)
        runCurrent()
    }
}

private class GroupFixture(private val context: Context, scope: CoroutineScope) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    val pacer = NotificationPostPacer(refillIntervalMillis = 1L, burstCapacity = 128)
    var now = 1_700_000_000_000L
    var childWrites = 0
    var summaryAttempts = 0
    var summaryCancelAttempts = 0
    var failRead = false
    var hiddenReads = 0
    var failChild = false
    var failSummary = false
    var dropSummaryCancels = 0
    val coordinator =
        NotificationGroupReconciler(
            context, scope, pacer,
            read = {
                if (failRead) throw IllegalStateException("tray unavailable")
                if (hiddenReads > 0) { hiddenReads--; emptyArray() } else it.activeNotifications
            },
            post = { compat, tag, id, notification ->
                summaryAttempts++
                if (failSummary) throw IllegalStateException("summary unavailable")
                compat.notify(tag, id, notification)
            },
            cancel = { compat, tag, id ->
                summaryCancelAttempts++
                if (dropSummaryCancels > 0) dropSummaryCancels-- else compat.cancel(tag, id)
            },
        )
    val presenter =
        LocalNotificationPresenter(
            context, shortcutPublisher = {}, nowMillis = { now }, postPacer = pacer,
            groupReconciliation = coordinator::request,
            notificationPoster = { compat, tag, id, notification ->
                if (failChild) throw IllegalStateException("child unavailable")
                childWrites++
                compat.notify(tag, id, notification)
            },
            avatarBitmapResolver = { null }, enrichmentLauncher = { block -> scope.launch { block() } },
        )

    suspend fun send(account: String, group: String, message: String, mention: Boolean = false): Notification {
        now += 6_000
        val update = alertBudgetUpdate(message, now, mention, account, group)
        assertTrue(presenter.show(update, shortNpub = { it }))
        val key = LocalNotificationFormatter.notificationDismissalKey(update)
        return manager.activeNotifications.single { it.tag == key.tag && it.id == key.id }.notification
    }

    fun summary(): Notification? = manager.activeNotifications.singleOrNull { it.tag == UserEventNotificationGroup.SUMMARY_TAG && it.id == UserEventNotificationGroup.SUMMARY_ID }?.notification
}
