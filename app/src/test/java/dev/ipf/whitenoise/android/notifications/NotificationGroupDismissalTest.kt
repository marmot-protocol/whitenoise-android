package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/** Real delete broadcasts and displayed-generation cleanup through the production receiver. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationGroupDismissalTest {
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
        manager.cancelAll()
    }

    @Test
    fun staleChildDeleteIntentCannotClearANewerCardOnTheSameKey() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            val old = fixture.send("account-a", "group-a", "old")
            val intent = shadowOf(old.deleteIntent).savedIntent
            fixture.send("account-a", "group-a", "new")
            dismissNotificationGroupGenerations(
                context,
                requireNotNull(UserEventNotificationGroup.dismissalChildren(intent)),
                pacer = fixture.pacer,
                request = fixture.coordinator::request,
            )
            settle()
            val live = manager.activeNotifications.single { UserEventNotificationGroup.child(it) != null }.notification
            assertEquals("new", live.extras.getString(EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
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
            dismissNotificationGroupGenerations(
                context,
                children,
                fixture.pacer,
                request = fixture.coordinator::request,
            )
            fixture.coordinator.request()
            settle()
            val live = manager.activeNotifications.single { UserEventNotificationGroup.child(it) != null }.notification
            assertEquals("new-b", live.extras.getString(EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX))
            assertEquals(
                "1 notification",
                requireNotNull(fixture.summary()).extras.getCharSequence(Notification.EXTRA_TEXT),
            )
            dismissNotificationGroupGenerations(
                context,
                children,
                fixture.pacer,
                request = fixture.coordinator::request,
            )
            assertEquals(1, manager.activeNotifications.count { UserEventNotificationGroup.child(it) != null })
        }

    @Test
    fun dismissReceiverFinishesOnceOnSuccessPlatformFailureAndTimeout() =
        runTest {
            val receiver = NotificationGroupDismissReceiver()
            val children = listOf(NotificationGroupChild("account|group", 0, "synthetic-generation"))
            var successFinishes = 0
            receiver.finishDismissal(context, children, finish = { successFinishes++ }, dismiss = {})
            assertEquals(1, successFinishes)
            var failureFinishes = 0
            receiver.finishDismissal(
                context,
                children,
                finish = { failureFinishes++ },
                dismiss = { throw IllegalStateException("platform failed") },
            )
            assertEquals(1, failureFinishes)
            var timeoutFinishes = 0
            receiver.finishDismissal(
                context,
                children,
                finish = { timeoutFinishes++ },
                dismiss = { kotlinx.coroutines.awaitCancellation() },
                budgetMs = 50L,
            )
            assertEquals(1, timeoutFinishes)
        }

    @Test
    fun aRealSummaryDeleteBroadcastRemovesOnlyItsDisplayedGenerations() =
        runTest {
            val fixture = GroupFixture(context, backgroundScope)
            fixture.send("account-a", "group-a", "old")
            settle()
            val intent = shadowOf(requireNotNull(fixture.summary()).deleteIntent).savedIntent
            fixture.send("account-b", "group-b", "later")
            context.sendBroadcast(intent)
            pumpingMainLooper {
                kotlinx.coroutines.withTimeout(10_000L) {
                    while (manager.activeNotifications.any { it.tag == "account-a|group-a" }) {
                        kotlinx.coroutines.delay(5L)
                    }
                }
            }
            assertTrue(manager.activeNotifications.any { it.tag == "account-b|group-b" })
        }

    private fun TestScope.settle() {
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2_000))
        advanceTimeBy(2_000)
        runCurrent()
    }
}
