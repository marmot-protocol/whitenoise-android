package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationReplyRaceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
        LocalNotificationPresenter(context).ensureChannels()
    }

    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
    }

    @Test
    fun replyHandledRepostCannotOverwriteNewerCardPostedDuringCriticalSection() {
        val conversation = conversationKey()
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val replyPresenter = LocalNotificationPresenter(context)
        val appStatePresenter = LocalNotificationPresenter(context)
        val replyReadCardA = CountDownLatch(1)
        val showAwaitingLock = CountDownLatch(1)
        val allowReplyWrite = CountDownLatch(1)
        val replyFinished = CountDownLatch(1)
        val showFinished = CountDownLatch(1)
        val replyFailure = AtomicReference<Throwable>()
        val showFailure = AtomicReference<Throwable>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.MARK_REPLY_HANDLED &&
                        barrier == ConversationCardBarrier.AFTER_READ &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        replyReadCardA.countDown()
                        check(allowReplyWrite.await(5, TimeUnit.SECONDS))
                    }
                }

                override fun onAwaitingLock(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        showAwaitingLock.countDown()
                    }
                }
            }

        Thread {
            try {
                assertTrue(replyPresenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))
            } catch (throwable: Throwable) {
                replyFailure.set(throwable)
            } finally {
                replyFinished.countDown()
            }
        }.start()
        assertTrue(replyReadCardA.await(5, TimeUnit.SECONDS))

        Thread {
            try {
                runBlocking {
                    assertTrue(
                        appStatePresenter.show(
                            messageUpdate("msg-b", previewText = "new during window", timestampMs = 2_000L),
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                showFailure.set(throwable)
            } finally {
                showFinished.countDown()
            }
        }.start()
        assertTrue(showAwaitingLock.await(5, TimeUnit.SECONDS))

        allowReplyWrite.countDown()
        assertTrue(replyFinished.await(5, TimeUnit.SECONDS))
        assertTrue(showFinished.await(5, TimeUnit.SECONDS))
        replyFailure.get()?.let { throw it }
        showFailure.get()?.let { throw it }

        replyPresenter.cancelRepliedConversationCardIfSameGeneration(conversation.tag, conversation.id, "msg-a")

        assertEquals(
            listOf(conversation.tag to conversation.id),
            manager.activeNotifications.map { it.tag to it.id },
        )
        assertEquals(
            "msg-b",
            manager.activeNotifications.single().notification.extras.getString(
                LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
            ),
        )
    }

    @Test
    fun replyCancelCannotDropNewerCardPostedDuringCriticalSection() {
        val conversation = conversationKey()
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val replyPresenter = LocalNotificationPresenter(context)
        val appStatePresenter = LocalNotificationPresenter(context)
        assertTrue(replyPresenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))

        val cancelReadCardA = CountDownLatch(1)
        val showAwaitingLock = CountDownLatch(1)
        val allowCancel = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
        val showFinished = CountDownLatch(1)
        val cancelFailure = AtomicReference<Throwable>()
        val showFailure = AtomicReference<Throwable>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.CANCEL_IF_SAME_GENERATION &&
                        barrier == ConversationCardBarrier.AFTER_READ &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        cancelReadCardA.countDown()
                        check(allowCancel.await(5, TimeUnit.SECONDS))
                    }
                }

                override fun onAwaitingLock(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        showAwaitingLock.countDown()
                    }
                }
            }

        Thread {
            try {
                replyPresenter.cancelRepliedConversationCardIfSameGeneration(conversation.tag, conversation.id, "msg-a")
            } catch (throwable: Throwable) {
                cancelFailure.set(throwable)
            } finally {
                cancelFinished.countDown()
            }
        }.start()
        assertTrue(cancelReadCardA.await(5, TimeUnit.SECONDS))

        Thread {
            try {
                runBlocking {
                    assertTrue(
                        appStatePresenter.show(
                            messageUpdate("msg-b", previewText = "new during window", timestampMs = 2_000L),
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                showFailure.set(throwable)
            } finally {
                showFinished.countDown()
            }
        }.start()
        assertTrue(showAwaitingLock.await(5, TimeUnit.SECONDS))

        allowCancel.countDown()
        assertTrue(cancelFinished.await(5, TimeUnit.SECONDS))
        assertTrue(showFinished.await(5, TimeUnit.SECONDS))
        cancelFailure.get()?.let { throw it }
        showFailure.get()?.let { throw it }

        assertEquals(
            listOf(conversation.tag to conversation.id),
            manager.activeNotifications.map { it.tag to it.id },
        )
        assertEquals(
            "msg-b",
            manager.activeNotifications.single().notification.extras.getString(
                LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX,
            ),
        )
    }

    private fun conversationKey() = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, GROUP)

    private fun messageUpdate(
        messageIdHex: String,
        previewText: String,
        timestampMs: Long,
    ) = NotificationUpdateFfi(
        notificationKey = "key",
        conversationKey = "conversation",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        accountRef = ACCOUNT,
        accountIdHex = ACCOUNT,
        groupIdHex = GROUP,
        groupName = "General",
        isDm = false,
        isMention = false,
        messageIdHex = messageIdHex,
        sender = user(displayName = "Alice"),
        receiver = user(accountIdHex = "self", displayName = "Me"),
        previewText = previewText,
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = timestampMs,
        isFromSelf = false,
    )

    private fun messagingNotification(
        messageIdHex: String?,
        vararg lines: Pair<String, Long>,
    ): android.app.Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        lines.forEach { (text, timestampMs) ->
            style.addMessage(text, timestampMs, Person.Builder().setName("Alice").build())
        }
        return NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .apply {
                messageIdHex?.let {
                    addExtras(
                        Bundle().apply {
                            putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, it)
                        },
                    )
                }
            }.build()
    }

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "group-a"
        const val TEST_CHANNEL = "reply-race-test"
    }
}
