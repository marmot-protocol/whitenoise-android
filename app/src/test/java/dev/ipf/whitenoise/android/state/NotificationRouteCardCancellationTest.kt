package dev.ipf.whitenoise.android.state

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The route awaits its cancellation lane without suppressing another account's cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationRouteCardCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Seeds identical conversation IDs under distinct account-owned notification keys. */
    @Before
    fun setUp() {
        manager.cancelAll()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT))
        listOf(SOURCE, TARGET).forEach { account ->
            val key = LocalNotificationFormatter.conversationDismissalKey(account, GROUP)
            manager.notify(key.tag, key.id, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(1).build())
        }
        assertEquals(2, manager.activeNotifications.size)
    }

    /** Removes only this isolated JVM fixture's platform state. */
    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(CHANNEL)
    }

    /** Main cannot complete the route before the injected cancellation dispatcher runs. */
    @Test
    fun dismissalAwaitsItsDispatcherAndKeepsSourceCards() =
        runTest {
            val state = appState(StandardTestDispatcher(testScheduler))
            var completed = false
            val dismissal =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.dismissNotificationRouteCards(TARGET, GROUP)
                    completed = true
                }
            assertFalse("route completed before its cancellation lane ran", completed)
            assertEquals(2, manager.activeNotifications.size)

            testScheduler.runCurrent()
            dismissal.join()

            assertTrue(completed)
            val sourceKey = LocalNotificationFormatter.conversationDismissalKey(SOURCE, GROUP)
            assertEquals(listOf(sourceKey.tag to sourceKey.id), manager.activeNotifications.map { it.tag to it.id })
        }

    /** A replaced route cannot resume its commit or cancel cards from queued obsolete work. */
    @Test
    fun cancelledRouteDoesNotCompleteItsQueuedDismissal() =
        runTest {
            val state = appState(StandardTestDispatcher(testScheduler))
            var completed = false
            val dismissal =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.dismissNotificationRouteCards(TARGET, GROUP)
                    completed = true
                }
            dismissal.cancel()
            testScheduler.runCurrent()
            dismissal.join()

            assertFalse(completed)
            assertEquals(2, manager.activeNotifications.size)
        }

    /** Avoids platform services and native account access while using the real notification presenter. */
    private fun appState(dispatcher: CoroutineDispatcher) =
        WhiteNoiseAppState(
            context = context,
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = TARGET,
            notificationCardCancellationDispatcher = dispatcher,
        )

    private companion object {
        const val CHANNEL = "route-cancellation-test"
        const val SOURCE = "source-account"
        const val TARGET = "target-account"
        val GROUP = "a".repeat(64)
    }
}
