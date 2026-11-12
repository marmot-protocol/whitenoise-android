package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Conversation dismissal keeps a replacement written after its cutoff, and still cancels old cards (#3711). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDismissalReplacementTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Starts each case with an empty registry and tray. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        manager.cancelAll()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Clears the tray and the test channel. */
    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
    }

    /**
     * The opening-time snapshot lists an old card under a key that a later show then replaced. The replacement's
     * accepted write is newer than the cutoff, so retirement must not cancel it on the stale listing (#3711).
     */
    @Test
    fun replacementPostedAfterTheSnapshotIsNotCancelledByItsStaleListing() {
        val account = "account-replaced"
        val group = "group-replaced"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        val reads = AtomicInteger()
        val oldCard = statusBarCard(conversation, postTime = 1_000)
        val newerCard = statusBarCard(conversation, postTime = Long.MAX_VALUE)
        val cancelled = mutableListOf<NotificationDismissalKey>()
        val presenter =
            LocalNotificationPresenter(
                context = context,
                groupReconciliation = {},
                notificationCanceller = { _, tag, id -> cancelled += NotificationDismissalKey(tag, id) },
                dismissalRetryDelay = {},
                activeNotificationsProvider = {
                    if (reads.getAndIncrement() == 0) {
                        // A show registered after the capture completes before retirement takes the key lock.
                        ConversationCardPostedRegistry.markPosted(
                            conversation.tag,
                            conversation.id,
                            recordedAtMs = System.currentTimeMillis() + LATER_POST_MS,
                        )
                        arrayOf(oldCard)
                    } else {
                        arrayOf(newerCard)
                    }
                },
            )

        assertTrue(runBlocking { presenter.dismissConversationMessages(account, group) })

        assertFalse("the newer replacement is not cancelled", conversation in cancelled)
        assertTrue(
            "the replacement's registry entry survives",
            ConversationCardPostedRegistry.hasPostedAfter(conversation.tag, conversation.id, 0L),
        )
    }

    /** An old card with no newer app write is still cancelled on its opening-time listing. */
    @Test
    fun oldListedCardWithoutAReplacementIsStillCancelled() {
        val account = "account-old-only"
        val group = "group-old-only"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        val reads = AtomicInteger()
        val cancelled = mutableListOf<NotificationDismissalKey>()
        val presenter =
            LocalNotificationPresenter(
                context = context,
                groupReconciliation = {},
                notificationCanceller = { _, tag, id -> cancelled += NotificationDismissalKey(tag, id) },
                dismissalRetryDelay = {},
                activeNotificationsProvider = {
                    if (reads.getAndIncrement() == 0) {
                        arrayOf(statusBarCard(conversation, postTime = 1_000))
                    } else {
                        emptyArray()
                    }
                },
            )

        assertTrue(runBlocking { presenter.dismissConversationMessages(account, group) })

        assertTrue(conversation in cancelled)
    }

    /** A platform card for [key] posted at [postTime]. */
    private fun statusBarCard(
        key: NotificationDismissalKey,
        postTime: Long,
    ) = StatusBarNotification(
        context.packageName,
        context.packageName,
        key.id,
        key.tag,
        1_000,
        0,
        0,
        notification(),
        Process.myUserHandle(),
        postTime,
    )

    /** A minimal card body on the test channel. */
    private fun notification() =
        NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .build()

    private companion object {
        const val TEST_CHANNEL = "replacement-dismissal-test"

        /** How far after the dismissal cutoff the injected replacement is recorded. */
        const val LATER_POST_MS = 60_000L
    }
}
