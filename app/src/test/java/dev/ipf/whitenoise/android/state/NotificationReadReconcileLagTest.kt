package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.service.notification.StatusBarNotification
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A read that lands while the platform has not yet listed a just-posted card is still reconciled (#2065). The
 * platform enqueues a post asynchronously, so the first read-change pass can miss the card; the follow-up pass
 * removes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [LaggingNotificationManager::class])
class NotificationReadReconcileLagTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val cardKey = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)

    /** Grants posting and starts from an empty tray. */
    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager().cancelAll()
        LaggingNotificationManager.reset()
    }

    /** Releases the hook and the tray. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager().cancelAll()
    }

    /** The follow-up pass removes a read card the first pass could not see yet. */
    @Test
    fun aReadLandingBeforeThePlatformListsTheCardIsStillReconciled() =
        runBlocking {
            val written = CountDownLatch(1)
            val read = AtomicBoolean(false)
            ConversationCardPostSynchronizer.testHook =
                object : ConversationCardTestHook {
                    override fun onBarrier(
                        op: ConversationCardOp,
                        barrier: ConversationCardBarrier,
                        notificationTag: String,
                        notificationId: Int,
                    ) {
                        if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_WRITE) {
                            written.countDown()
                        }
                    }
                }
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP_ID,
                    messageIdHex = MESSAGE_ID,
                    onChatListRow = { _, _ -> readRow().takeIf { read.get() } },
                    onTimelineMessages = { _, _ -> TimelinePageFfi(listOf(record()), false, false) },
                )
            try {
                fixture.bootstrap()
                fixture.awaitRuntimeEventSubscription()
                awaitLatch(written)
                read.set(true)
                fixture.emitRuntimeEvent(readStateChanged())

                withTimeout(AWAIT_TIMEOUT_MS) {
                    // Past the platform's listing lag, the card must have been listed and then removed.
                    while (!LaggingNotificationManager.settled(cardKey.tag) || cardListed()) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10L))
                        delay(10L)
                    }
                }
            } finally {
                fixture.close()
            }
        }

    /** Whether the platform currently lists the fixture card. */
    private fun cardListed() = manager().activeNotifications.any { it.tag == cardKey.tag && it.id == cardKey.id }

    /** The platform notification manager. */
    private fun manager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Pumps the main looper while waiting for [latch]. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!latch.await(1, TimeUnit.MILLISECONDS)) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** MDK's report that the fixture conversation's read state changed. */
    private fun readStateChanged(): MarmotEventFfi =
        MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = ACCOUNT_REF,
                accountLabel = ACCOUNT_REF,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = GROUP_ID,
                        messages = emptyList(),
                        changes = emptyList(),
                        chatListRow = readRow(),
                        chatListTrigger = ChatListUpdateTriggerFfi.UNREAD_CHANGED,
                    ),
            ),
        )

    /** The fixture message's timeline row. */
    private fun record() = requireNotNull(projectedTimelineMessage(timelineAppMessage(MESSAGE_ID, 12uL)).projected)

    /** A chat-list row whose read marker is the fixture message. */
    private fun readRow() = chatRow(GROUP_ID).copy(lastReadMessageIdHex = MESSAGE_ID, lastReadTimelineAt = 12uL)

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val GROUP_ID = "ab12cd34"
        val MESSAGE_ID = "ab".repeat(32)
        const val AWAIT_TIMEOUT_MS = 20_000L
    }
}

/** A notification manager that, like the platform's asynchronous enqueue, lists a new post only after a short lag. */
@Implements(NotificationManager::class)
class LaggingNotificationManager : ShadowNotificationManager() {
    /** Records when each card was first posted before delegating to the normal shadow. */
    @Implementation
    override fun notify(
        tag: String?,
        id: Int,
        notification: Notification,
    ) {
        posted.putIfAbsent("$tag|$id", System.currentTimeMillis())
        super.notify(tag, id, notification)
    }

    /** Omits cards posted less than [LAG_MS] ago, as the platform does until its enqueue completes. */
    @Implementation
    override fun getActiveNotifications(): Array<StatusBarNotification> {
        val now = System.currentTimeMillis()
        return super
            .getActiveNotifications()
            .filter { now - (posted["${it.tag}|${it.id}"] ?: 0L) >= LAG_MS }
            .toTypedArray()
    }

    companion object {
        private const val LAG_MS = 500L
        private val posted = ConcurrentHashMap<String, Long>()

        /** Forgets every recorded post. */
        fun reset() {
            posted.clear()
        }

        /** Whether the card with [tag] was posted and its listing lag has elapsed. */
        fun settled(tag: String): Boolean {
            val at = posted.entries.firstOrNull { it.key.startsWith("$tag|") }?.value ?: return false
            return System.currentTimeMillis() - at >= LAG_MS
        }
    }
}
