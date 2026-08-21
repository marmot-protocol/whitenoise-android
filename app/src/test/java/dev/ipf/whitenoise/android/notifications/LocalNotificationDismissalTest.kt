package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.state.dismissConversationNotificationsOnOpen
import dev.ipf.whitenoise.android.ui.navigation.selectedConversationAccountRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationDismissalTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
    }

    @Test
    fun dismissConversationMessagesReadsActiveNotificationsOffMainThread() {
        val account = "account-a"
        val group = "group-a"
        manager.notify(
            "invite-target",
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, group)
                    },
            ),
        )

        val readThread = AtomicReference<Thread>()
        val providerInvoked = AtomicBoolean(false)
        val mainLooperCallbackRan = AtomicBoolean(false)
        val presenter =
            LocalNotificationPresenter(context) { notificationManager ->
                providerInvoked.set(true)
                readThread.set(Thread.currentThread())
                notificationManager.activeNotifications
            }
        val failure = AtomicReference<Throwable>()
        Handler(Looper.getMainLooper()).post {
            mainLooperCallbackRan.set(true)
            runCatching {
                assertTrue(runBlocking { presenter.dismissConversationMessages(account, group) })
                assertNotNull(readThread.get())
                assertNotEquals(Looper.getMainLooper().thread, readThread.get())
            }.onFailure(failure::set)
        }
        shadowOf(Looper.getMainLooper()).idle()
        failure.get()?.let { throw it }
        assertTrue(mainLooperCallbackRan.get())
        assertTrue(providerInvoked.get())
    }

    @Test
    fun dismissConversationMessagesRethrowsCancellationFromActiveNotificationsRead() {
        val account = "account-a"
        val group = "group-a"
        manager.notify(
            "invite-target",
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, group)
                    },
            ),
        )
        val presenter =
            LocalNotificationPresenter(context) { _ ->
                throw CancellationException("cancelled during activeNotifications read")
            }

        assertThrows(CancellationException::class.java) {
            runBlocking { presenter.dismissConversationMessages(account, group) }
        }

        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun dismissConversationMessagesClearsSiblingCardsAndInviteOnlyForTargetConversation() {
        val account = "account-a"
        val group = "group-a"
        val siblingKeys =
            listOf(
                LocalNotificationFormatter.conversationDismissalKey(account, group),
                LocalNotificationFormatter.reactionDismissalKey(account, group),
                LocalNotificationFormatter.mentionDismissalKey(account, group),
                LocalNotificationFormatter.agentActivityDismissalKey(account, group),
            )
        siblingKeys.forEach { key -> manager.notify(key.tag, key.id, notification()) }
        manager.notify(
            "invite-target",
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, group)
                    },
            ),
        )
        val other = LocalNotificationFormatter.conversationDismissalKey("account-b", "group-b")
        manager.notify(other.tag, other.id, notification())

        assertTrue(runBlocking { LocalNotificationPresenter(context).dismissConversationMessages(account, group) })

        val remaining = manager.activeNotifications.map { it.tag to it.id }
        assertEquals(listOf(other.tag to other.id), remaining)
    }

    @Test
    fun notificationRoutedOpenBeforeAccountSwitchKeepsSourceAccountCards() {
        val sourceAccount = "account-a"
        val targetAccount = "account-b"
        val sharedGroup = "group-shared-by-both-accounts"
        val sourceKeys = postConversationCards(sourceAccount, sharedGroup, "invite-source")
        postConversationCards(targetAccount, sharedGroup, "invite-target")
        val lateSourceInvite = "invite-source-during-route" to 42
        val postedLateSourceInvite = AtomicBoolean(false)
        val presenter =
            LocalNotificationPresenter(context) { notificationManager ->
                if (postedLateSourceInvite.compareAndSet(false, true)) {
                    notificationManager.notify(
                        lateSourceInvite.first,
                        lateSourceInvite.second,
                        notification(
                            extras =
                                Bundle().apply {
                                    putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, sourceAccount)
                                    putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, sharedGroup)
                                },
                        ),
                    )
                }
                notificationManager.activeNotifications
            }
        val visibleAccount =
            selectedConversationAccountRef(
                pinnedAccountRef = targetAccount,
                activeAccountRef = sourceAccount,
            )

        runBlocking {
            dismissConversationNotificationsOnOpen(visibleAccount, sharedGroup) { accountRef, groupIdHex ->
                presenter.dismissConversationMessages(accountRef, groupIdHex)
            }
        }

        assertEquals(
            (sourceKeys + lateSourceInvite).toSet(),
            manager.activeNotifications.map { it.tag to it.id }.toSet(),
        )
    }

    @Test
    fun actionDismissalCancelsTheReadOrRepliedCardBeforeInspectingSiblings() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        val reaction = LocalNotificationFormatter.reactionDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        manager.notify(reaction.tag, reaction.id, notification())
        val baseline = manager.activeNotifications.maxOf { it.postTime }
        val providerInvoked = AtomicBoolean(false)
        val presenter =
            LocalNotificationPresenter(context) { notificationManager ->
                providerInvoked.set(true)
                assertFalse(
                    notificationManager.activeNotifications.any {
                        it.tag == conversation.tag && it.id == conversation.id
                    },
                )
                notificationManager.activeNotifications
            }

        assertTrue(
            presenter.dismissActionNotificationAndOlderSiblings(
                notificationTag = conversation.tag,
                notificationId = conversation.id,
                actedMessageIdHex = "msg-a",
                accountRef = account,
                groupIdHex = group,
                sinceMs = baseline,
            ),
        )

        assertTrue(providerInvoked.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun actionDismissalKeepsConversationCardUpdatedToANewerMessage() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-b", "newer" to 2_000L))

        assertTrue(
            LocalNotificationPresenter(context).dismissActionNotificationAndOlderSiblings(
                notificationTag = conversation.tag,
                notificationId = conversation.id,
                actedMessageIdHex = "msg-a",
                accountRef = account,
                groupIdHex = group,
                sinceMs = manager.activeNotifications.single().postTime - 1L,
            ),
        )

        assertEquals("msg-b", LocalNotificationPresenter(context).conversationCardMessageIdHex(conversation.tag, conversation.id))
    }

    @Test
    fun siblingDismissalClearsSiblingsAtOrBeforeBaselineButLeavesTheMessageCard() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        val reaction = LocalNotificationFormatter.reactionDismissalKey(account, group)
        val mention = LocalNotificationFormatter.mentionDismissalKey(account, group)
        val agentActivity = LocalNotificationFormatter.agentActivityDismissalKey(account, group)
        listOf(conversation, reaction, mention, agentActivity).forEach { manager.notify(it.tag, it.id, notification()) }
        manager.notify(
            "invite-target",
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, group)
                    },
            ),
        )
        // Baseline at/after every posted card, so each sibling counts as "already
        // present" and is cleared. The message card is the caller's to cancel.
        val baseline = manager.activeNotifications.maxOf { it.postTime }

        assertTrue(
            LocalNotificationPresenter(context)
                .dismissConversationSiblingCardsNotNewerThan(account, group, sinceMs = baseline),
        )

        val remaining = manager.activeNotifications.map { it.tag to it.id }
        assertEquals(listOf(conversation.tag to conversation.id), remaining)
    }

    @Test
    fun siblingDismissalKeepsSiblingsPostedAfterBaseline() {
        val account = "account-a"
        val group = "group-a"
        val reaction = LocalNotificationFormatter.reactionDismissalKey(account, group)
        manager.notify(reaction.tag, reaction.id, notification())
        // Baseline strictly before the card's post time, so it counts as newer
        // (arrived mid-window) and must survive.
        val reactionPostTime = manager.activeNotifications.single().postTime

        assertTrue(
            LocalNotificationPresenter(context)
                .dismissConversationSiblingCardsNotNewerThan(account, group, sinceMs = reactionPostTime - 1),
        )

        assertEquals(
            listOf(reaction.tag to reaction.id),
            manager.activeNotifications.map { it.tag to it.id },
        )
    }

    @Test
    fun siblingDismissalKeepsInviteCardPostedAfterBaseline() {
        val account = "account-a"
        val group = "group-a"
        manager.notify(
            "invite-target",
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, group)
                    },
            ),
        )
        // Baseline strictly before the invite's post time exercises the
        // shouldDismissInvite + postTime survive branch (and its tapTokens skip).
        val invitePostTime = manager.activeNotifications.single().postTime

        assertTrue(
            LocalNotificationPresenter(context)
                .dismissConversationSiblingCardsNotNewerThan(account, group, sinceMs = invitePostTime - 1),
        )

        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun replyDismissClearsMessageCardWhenSameGeneration() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val presenter = LocalNotificationPresenter(context)

        assertTrue(presenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))
        presenter.cancelRepliedConversationCardIfSameGeneration(conversation.tag, conversation.id, "msg-a")

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun replyDismissPreservesMessageCardWhenNewerGenerationArrivesDuringWindow() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val presenter = LocalNotificationPresenter(context)

        assertTrue(presenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))
        manager.notify(
            conversation.tag,
            conversation.id,
            messagingNotification(
                "msg-b",
                "hello" to 1_000L,
                "new during window" to 2_000L,
            ),
        )
        presenter.cancelRepliedConversationCardIfSameGeneration(conversation.tag, conversation.id, "msg-a")

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
    fun replyHandledRepostPreservesGenerationMarker() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification("msg-a", "hello" to 1_000L))
        val presenter = LocalNotificationPresenter(context)

        assertTrue(presenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))

        assertEquals(
            "msg-a",
            presenter.conversationCardMessageIdHex(conversation.tag, conversation.id),
        )
    }

    @Test
    fun replyDismissFailsClosedWhenGenerationMarkerMissing() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        manager.notify(conversation.tag, conversation.id, messagingNotification(null, "hello" to 1_000L))
        val presenter = LocalNotificationPresenter(context)

        assertTrue(presenter.markDirectReplyHandled(conversation.tag, conversation.id, "reply"))
        presenter.cancelRepliedConversationCardIfSameGeneration(conversation.tag, conversation.id, "msg-a")

        assertEquals(1, manager.activeNotifications.size)
    }

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

    private fun notification(extras: Bundle? = null) =
        NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .apply { if (extras != null) addExtras(extras) }
            .build()

    private fun postConversationCards(
        accountRef: String,
        groupIdHex: String,
        inviteTag: String,
    ): List<Pair<String?, Int>> {
        val keys =
            listOf(
                LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex),
                LocalNotificationFormatter.reactionDismissalKey(accountRef, groupIdHex),
                LocalNotificationFormatter.mentionDismissalKey(accountRef, groupIdHex),
                LocalNotificationFormatter.agentActivityDismissalKey(accountRef, groupIdHex),
            )
        keys.forEach { key -> manager.notify(key.tag, key.id, notification()) }
        manager.notify(
            inviteTag,
            41,
            notification(
                extras =
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, accountRef)
                        putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, groupIdHex)
                    },
            ),
        )
        return keys.map { it.tag to it.id } + (inviteTag to 41)
    }

    private companion object {
        const val TEST_CHANNEL = "dismissal-test"
    }
}
