package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.runBlocking
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
 * The presenter's first posts through a real alert budget: a catch-up backlog rings once for the whole
 * cohort, a live burst rings once, a rejected write hands its ring back, and every written card reaches
 * the notification manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationPresenterAlertBudgetTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
    private val posted = mutableListOf<Notification>()
    private var rejectNextWrite = false
    private var now = 1_700_000_000_000L
    private var elapsed = 0L
    private val catchUpWindow = NotificationCatchUpWindow(clock = { elapsed })
    private lateinit var presenter: LocalNotificationPresenter

    /** Grants the permission, pins the clock and records every write instead of consulting the shade. */
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
                    if (rejectNextWrite) {
                        rejectNextWrite = false
                        throw IllegalStateException("shade rejected the write")
                    }
                    posted += notification
                    notificationManager.notify(tag, id, notification)
                },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        presenter.ensureChannels()
    }

    /** Coming back online writes every backlog card and rings for the first one only, mention or not. */
    @Test
    fun reconnectBacklogRingsOnce() =
        runBlocking {
            catchUpWindow.open()
            repeat(3) { index ->
                now += 20_000
                assertTrue(
                    presenter.show(
                        alertBudgetUpdate(
                            messageIdHex = "old-$index",
                            timestampMs = now - 600_000,
                            isMention = index == 2,
                        ),
                        shortNpub = { it },
                    ),
                )
            }
            catchUpWindow.close()
            elapsed += NOTIFICATION_CATCH_UP_TAIL_MS + 1
            now += 20_000
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "live", timestampMs = now), shortNpub = { it }),
            )

            assertEquals(4, posted.size)
            assertEquals(
                "one ring for the cohort, then the live message rings again",
                listOf(false, true, true, false),
                posted.map { it.isOnlyAlertOnce() },
            )
        }

    /** A live burst rings for its first card; the followers join it silently; the next burst rings again. */
    @Test
    fun liveBurstRingsOnce() =
        runBlocking {
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "first", timestampMs = now), shortNpub = { it }),
            )
            now += 1_000
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "second", timestampMs = now), shortNpub = { it }),
            )
            now += 1_000
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "third", timestampMs = now), shortNpub = { it }),
            )
            now += NOTIFICATION_ALERT_BURST_WINDOW_MS
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "later", timestampMs = now), shortNpub = { it }),
            )

            assertEquals(listOf(false, true, true, false), posted.map { it.isOnlyAlertOnce() })
        }

    /** A fresh mention rings even inside a burst. */
    @Test
    fun freshMentionRingsInsideABurst() =
        runBlocking {
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "first", timestampMs = now), shortNpub = { it }),
            )
            now += 1_000
            assertTrue(
                presenter.show(
                    alertBudgetUpdate(messageIdHex = "mention", timestampMs = now, isMention = true),
                    shortNpub = { it },
                ),
            )
            assertFalse(posted.last().isOnlyAlertOnce())
        }

    /** A card the shade rejects hands its ring back, so the next card rings instead of joining a phantom burst. */
    @Test
    fun aRejectedWriteHandsTheRingBack() =
        runBlocking {
            rejectNextWrite = true
            assertFalse(presenter.show(alertBudgetUpdate(messageIdHex = "lost", timestampMs = now), shortNpub = { it }))
            now += 1_000
            assertTrue(
                presenter.show(alertBudgetUpdate(messageIdHex = "next", timestampMs = now), shortNpub = { it }),
            )

            assertEquals(1, posted.size)
            assertFalse(posted.single().isOnlyAlertOnce())
        }
}
