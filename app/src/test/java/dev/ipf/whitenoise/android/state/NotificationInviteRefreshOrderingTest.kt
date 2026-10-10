package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.groupInviteUpdate
import dev.ipf.whitenoise.android.notifications.notificationUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

private const val INVITE_AWAIT_TIMEOUT_MS = 30_000L
private const val NO_EXTRA_WRITE_WINDOW_MS = 250L

/**
 * Orders the invite identity refresh against the live invite card it corrects (#2412).
 *
 * The refresh passes `silentUpdate` without `replaceCurrentMessage`, so it must reach the platform as an
 * update that keeps the first card's group alerting and rank. Otherwise SystemUI withdraws the banner that the
 * first post is still showing, just to replace a fallback name with the resolved one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationInviteRefreshOrderingTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val invite = groupInviteUpdate(sender = notificationUser(displayName = null))
    private val inviteKey = invite.notificationKey

    /** Grants posting and clears any card left by an earlier scenario. */
    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    /** Releases the synchronizer hook and removes the synthetic invite card. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
    }

    /** A resolved sender name rewrites the live invite once, as an update that keeps its alerting and rank. */
    @Test
    fun resolvedInviteSenderRefreshesTheLiveCardWithoutEndingItsHeadsUp() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = null,
                )
            try {
                withWriteCount { writes ->
                    fixture.bootstrap()
                    // A warm name cache makes the first card and the post-deadline resolution agree, so the late
                    // text correction finds nothing to change and leaves the shared write permit to the refresh.
                    fixture.warmNpubCache()
                    fixture.runWithMainLooperPumping { fixture.appState.processNotificationUpdateForTest(invite) }
                    val first = awaitInviteCard(fixture) { true }
                    fixture.runWithMainLooperPumping { delay(NO_EXTRA_WRITE_WINDOW_MS) }

                    assertEquals(0, first.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                    assertEquals(Notification.GROUP_ALERT_CHILDREN, first.groupAlertBehavior)
                    assertEquals(1, writes.get())

                    fixture.appState.applyAccountSwitchProfileSeed(
                        AccountSwitchProfileSeed(
                            accountIdHex = invite.sender.accountIdHex,
                            profile = null,
                            displayName = RESOLVED_NAME,
                            avatarUrl = null,
                        ),
                    )
                    val refreshed = awaitInviteCard(fixture) { it.bodyText().contains(RESOLVED_NAME) }
                    delay(NO_EXTRA_WRITE_WINDOW_MS)

                    assertTrue(refreshed.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                    assertEquals(first.groupAlertBehavior, refreshed.groupAlertBehavior)
                    assertEquals(first.group, refreshed.group)
                    assertEquals(first.sortKey, refreshed.sortKey)
                    assertEquals(first.channelId, refreshed.channelId)
                    assertNull(refreshed.sound)
                    assertEquals(0, refreshed.defaults)
                    assertEquals(2, writes.get())
                    assertEquals(1, manager.activeNotifications.count { it.tag == inviteKey })
                }
            } finally {
                fixture.close()
            }
        }

    /** Polls the invite card while pumping Robolectric main, returning the first card that satisfies [accept]. */
    private suspend fun awaitInviteCard(
        fixture: NotificationBootstrapTestFixture,
        accept: (Notification) -> Boolean,
    ): Notification =
        fixture.runWithMainLooperPumping {
            withTimeout(INVITE_AWAIT_TIMEOUT_MS) {
                var card = liveInviteCard()
                while (card?.let(accept) != true) {
                    delay(10L)
                    card = liveInviteCard()
                }
                checkNotNull(card)
            }
        }

    /** The platform's current invite card for the synthetic key, or null when none is showing. */
    private fun liveInviteCard(): Notification? =
        manager.activeNotifications
            .firstOrNull { it.tag == inviteKey && it.id == LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID }
            ?.notification

    /** The visible body of an invite card as plain text. */
    private fun Notification.bodyText(): String = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    /** Counts serialized platform writes for the synthetic invite while [block] runs. */
    private suspend fun withWriteCount(block: suspend (AtomicInteger) -> Unit) {
        val writes = AtomicInteger(0)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                /** Counts a completed write for the invite's own key only. */
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        barrier == ConversationCardBarrier.AFTER_WRITE &&
                        notificationTag == inviteKey
                    ) {
                        writes.incrementAndGet()
                    }
                }
            }
        try {
            block(writes)
        } finally {
            ConversationCardPostSynchronizer.testHook = null
        }
    }

    private companion object {
        const val RESOLVED_NAME = "Alice Resolved"
    }
}
