package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * One catch-up cohort through the real presenter across accounts and conversations: one ring per
 * account, every card written, silent cards keep their history and actions, and a cancelled write
 * hands its ring back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationPresenterCatchUpCohortTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
    private val posted = mutableListOf<Triple<String, Int, Notification>>()
    private var now = 1_700_000_000_000L
    private var elapsed = 0L
    private val catchUpWindow = NotificationCatchUpWindow(clock = { elapsed })
    private lateinit var presenter: LocalNotificationPresenter

    /** Grants the permission, pins the clocks, records every write and opens one catch-up cohort. */
    @Before
    fun setUp() {
        manager.cancelAll()
        posted.clear()
        presenter =
            LocalNotificationPresenter(
                context = context,
                nowMillis = { now },
                alertBudget = NotificationAlertBudget(catchUpWindow),
                notificationPoster = { notificationManager, tag, id, notification ->
                    posted += Triple(tag, id, notification)
                    notificationManager.notify(tag, id, notification)
                },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        presenter.ensureChannels()
        catchUpWindow.open()
    }

    /** Removes the barrier hook so later tests run unpaused. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
    }

    /** Two accounts with two conversations each: four cards on four keys, one ring per account. */
    @Test
    fun backlogAcrossAccountsAndConversationsRingsOncePerAccount() =
        runBlocking {
            for (account in listOf("account-a", "account-b")) {
                for (group in listOf("group-1", "group-2")) {
                    now += 5_000
                    assertTrue(
                        presenter.show(
                            alertBudgetUpdate("m-$account-$group", now, accountRef = account, groupIdHex = group),
                            shortNpub = { it },
                        ),
                    )
                }
            }

            assertEquals(4, posted.size)
            assertEquals("each conversation keeps its own card", 4, posted.map { it.first to it.second }.toSet().size)
            assertEquals(
                "the first card of each account rings, the rest of its cohort joins silently",
                listOf(false, true, false, true),
                posted.map { it.third.isOnlyAlertOnce() },
            )
        }

    /** A silent cohort card carries the same history growth and actions an alerting card would. */
    @Test
    fun silentCatchUpCardKeepsHistoryAndActions() =
        runBlocking {
            assertTrue(presenter.show(alertBudgetUpdate("first", now), shortNpub = { it }))
            now += 1_000
            assertTrue(presenter.show(alertBudgetUpdate("second", now), shortNpub = { it }))

            val (loud, silent) = posted.map { it.third }
            assertFalse(loud.isOnlyAlertOnce())
            assertTrue(silent.isOnlyAlertOnce())
            val history =
                checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(silent)).messages
            assertEquals(listOf("hi first", "hi second"), history.map { it.text.toString() })
            assertEquals(2, silent.actions.size)
            assertEquals(loud.actions.map { it.title }, silent.actions.map { it.title })
        }

    /** A write cancelled between decision and notify releases its ring, so the next card of the cohort rings. */
    @Test
    fun aCancelledWriteHandsTheRingBack() =
        runBlocking {
            ConversationCardPostSynchronizer.testHook =
                object : ConversationCardTestHook {
                    override fun onBarrier(
                        op: ConversationCardOp,
                        barrier: ConversationCardBarrier,
                        notificationTag: String,
                        notificationId: Int,
                    ) {
                        if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.BEFORE_WRITE) {
                            ConversationCardPostSynchronizer.testHook = null
                            throw CancellationException("runtime stopped mid-post")
                        }
                    }
                }
            val cancelled =
                runCatching { presenter.show(alertBudgetUpdate("lost", now), shortNpub = { it }) }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            assertTrue(posted.isEmpty())

            now += 1_000
            assertTrue(presenter.show(alertBudgetUpdate("next", now), shortNpub = { it }))
            assertFalse("the released ring goes to the next card", posted.single().third.isOnlyAlertOnce())
        }
}
