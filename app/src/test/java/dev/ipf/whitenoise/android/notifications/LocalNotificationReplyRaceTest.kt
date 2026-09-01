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
import dev.ipf.whitenoise.android.state.StalenessGuard
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
                            shortNpub = { "npub1test" },
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

        assertActiveConversationCard(
            conversation = conversation,
            expectedMessageIdHex = "msg-b",
            exactMessageTexts = listOf("hello", "new during window"),
        )
    }

    @Test
    fun concurrentShowCannotDropCarriedHistoryReadOutsideLock() {
        val conversation = conversationKey()
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val presenterB = LocalNotificationPresenter(context)
        val presenterC = LocalNotificationPresenter(context)
        val showBReadCarried = CountDownLatch(1)
        val showCAwaitingLock = CountDownLatch(1)
        val allowShowBContinue = CountDownLatch(1)
        val showBFinished = CountDownLatch(1)
        val showCFinished = CountDownLatch(1)
        val showBFailure = AtomicReference<Throwable>()
        val showCFailure = AtomicReference<Throwable>()
        val showBHoldingAfterRead = AtomicBoolean(false)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        barrier == ConversationCardBarrier.AFTER_READ &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        showBHoldingAfterRead.set(true)
                        showBReadCarried.countDown()
                        check(allowShowBContinue.await(5, TimeUnit.SECONDS))
                        showBHoldingAfterRead.set(false)
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
                        notificationId == conversation.id &&
                        showBHoldingAfterRead.get()
                    ) {
                        showCAwaitingLock.countDown()
                    }
                }
            }

        Thread {
            try {
                runBlocking {
                    assertTrue(
                        presenterB.show(
                            messageUpdate("msg-b", previewText = "message-b", timestampMs = 2_000L),
                            shortNpub = { "npub1test" },
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                showBFailure.set(throwable)
            } finally {
                showBFinished.countDown()
            }
        }.start()
        assertTrue(showBReadCarried.await(5, TimeUnit.SECONDS))

        Thread {
            try {
                runBlocking {
                    assertTrue(
                        presenterC.show(
                            messageUpdate("msg-c", previewText = "message-c", timestampMs = 3_000L),
                            shortNpub = { "npub1test" },
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                showCFailure.set(throwable)
            } finally {
                showCFinished.countDown()
            }
        }.start()
        assertTrue(showCAwaitingLock.await(5, TimeUnit.SECONDS))

        allowShowBContinue.countDown()
        assertTrue(showBFinished.await(5, TimeUnit.SECONDS))
        assertTrue(showCFinished.await(5, TimeUnit.SECONDS))
        showBFailure.get()?.let { throw it }
        showCFailure.get()?.let { throw it }

        assertActiveConversationCard(
            conversation = conversation,
            expectedMessageIdHex = "msg-c",
            exactMessageTexts = listOf("hello", "message-b", "message-c"),
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
                            shortNpub = { "npub1test" },
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

        assertActiveConversationCard(
            conversation = conversation,
            expectedMessageIdHex = "msg-b",
            requiredMessageTexts = listOf("new during window"),
        )
    }

    /** Rejects a notification whose foreground-conversation eligibility changes during show. */
    @Test
    fun visibleConversationChangeAfterShowRegistrationPreventsThePost() {
        val presenter = LocalNotificationPresenter(context)
        val postEpoch = StalenessGuard()
        val capturedEpoch = postEpoch.capture()
        val showRegistered = CountDownLatch(1)
        val releaseShow = CountDownLatch(1)
        val showFinished = CountDownLatch(1)
        val showResult = AtomicReference<Boolean>()
        val showFailure = AtomicReference<Throwable>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_REGISTER) {
                        showRegistered.countDown()
                        check(releaseShow.await(5, TimeUnit.SECONDS))
                    }
                }
            }

        Thread {
            try {
                showResult.set(
                    runBlocking {
                        presenter.show(
                            messageUpdate("msg-after-open", previewText = "do not resurrect", timestampMs = 2_000L),
                            isPostStillAllowed = { postEpoch.isCurrent(capturedEpoch) },
                            shortNpub = { "npub1test" },
                        )
                    },
                )
            } catch (throwable: Throwable) {
                showFailure.set(throwable)
            } finally {
                showFinished.countDown()
            }
        }.start()

        assertTrue(showRegistered.await(5, TimeUnit.SECONDS))
        postEpoch.advance()
        postEpoch.advance()
        releaseShow.countDown()
        assertTrue(showFinished.await(5, TimeUnit.SECONDS))
        showFailure.get()?.let { throw it }
        assertFalse(showResult.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun conversationDismissWaitsForInFlightPostThenCancelsIt() {
        val conversation = conversationKey()
        val presenter = LocalNotificationPresenter(context)
        val showBeforeWrite = CountDownLatch(1)
        val dismissAwaitingLock = CountDownLatch(1)
        val allowShowWrite = CountDownLatch(1)
        val showFinished = CountDownLatch(1)
        val dismissFinished = CountDownLatch(1)
        val showFailure = AtomicReference<Throwable>()
        val dismissFailure = AtomicReference<Throwable>()
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        barrier == ConversationCardBarrier.BEFORE_WRITE &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        showBeforeWrite.countDown()
                        check(allowShowWrite.await(5, TimeUnit.SECONDS))
                    }
                }

                override fun onAwaitingLock(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.DISMISS_CANCEL &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        dismissAwaitingLock.countDown()
                    }
                }
            }

        Thread {
            try {
                runBlocking {
                    assertTrue(
                        presenter.show(
                            messageUpdate("msg-a", previewText = "new", timestampMs = 1_000L),
                            shortNpub = { "npub1test" },
                        ),
                    )
                }
            } catch (throwable: Throwable) {
                showFailure.set(throwable)
            } finally {
                showFinished.countDown()
            }
        }.start()
        assertTrue(showBeforeWrite.await(5, TimeUnit.SECONDS))

        Thread {
            try {
                assertTrue(runBlocking { presenter.dismissConversationMessages(ACCOUNT, GROUP) })
            } catch (throwable: Throwable) {
                dismissFailure.set(throwable)
            } finally {
                dismissFinished.countDown()
            }
        }.start()
        assertTrue(dismissAwaitingLock.await(5, TimeUnit.SECONDS))

        allowShowWrite.countDown()
        assertTrue(showFinished.await(5, TimeUnit.SECONDS))
        assertTrue(dismissFinished.await(5, TimeUnit.SECONDS))
        showFailure.get()?.let { throw it }
        dismissFailure.get()?.let { throw it }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun conversationDismissInvalidatesPostThatHasRegisteredButNotReachedTheLock() {
        val conversation = conversationKey()
        val presenter = LocalNotificationPresenter(context)
        val showRegistered = CountDownLatch(1)
        val allowShowToContinue = CountDownLatch(1)
        val showFinished = CountDownLatch(1)
        val showResult = AtomicBoolean(true)
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
                        op == ConversationCardOp.SHOW_NOTIFY &&
                        barrier == ConversationCardBarrier.AFTER_REGISTER &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        showRegistered.countDown()
                        check(allowShowToContinue.await(5, TimeUnit.SECONDS))
                    }
                }
            }

        Thread {
            try {
                showResult.set(
                    runBlocking {
                        presenter.show(
                            messageUpdate("msg-a", previewText = "stale", timestampMs = 1_000L),
                            shortNpub = { "npub1test" },
                        )
                    },
                )
            } catch (throwable: Throwable) {
                showFailure.set(throwable)
            } finally {
                showFinished.countDown()
            }
        }.start()
        assertTrue(showRegistered.await(5, TimeUnit.SECONDS))

        assertTrue(runBlocking { presenter.dismissConversationMessages(ACCOUNT, GROUP) })
        allowShowToContinue.countDown()

        assertTrue(showFinished.await(5, TimeUnit.SECONDS))
        showFailure.get()?.let { throw it }
        assertTrue(!showResult.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun assertActiveConversationCard(
        conversation: NotificationDismissalKey,
        expectedMessageIdHex: String,
        exactMessageTexts: List<String>? = null,
        requiredMessageTexts: List<String> = emptyList(),
    ) {
        assertEquals(
            listOf(conversation.tag to conversation.id),
            manager.activeNotifications.map { it.tag to it.id },
        )
        val active = manager.activeNotifications.single().notification
        assertEquals(
            expectedMessageIdHex,
            active.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX),
        )
        val messageTexts = messagingMessageTexts(active)
        if (exactMessageTexts != null) {
            assertEquals(exactMessageTexts, messageTexts)
        }
        requiredMessageTexts.forEach { required ->
            assertTrue("$required missing from $messageTexts", messageTexts.contains(required))
        }
    }

    private fun messagingMessageTexts(notification: android.app.Notification): List<String> {
        val style =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?: error("expected MessagingStyle")
        return style.messages.map { it.text?.toString().orEmpty() }
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
        trafficClass = dev.ipf.marmotkit.NotificationTrafficClassFfi.STANDARD,
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
