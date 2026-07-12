package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
    fun dismissConversationMessagesClearsSiblingCardsAndInviteOnlyForTargetConversation() {
        val account = "account-a"
        val group = "group-a"
        val siblingKeys =
            listOf(
                LocalNotificationFormatter.conversationDismissalKey(account, group),
                LocalNotificationFormatter.reactionDismissalKey(account, group),
                LocalNotificationFormatter.mentionDismissalKey(account, group),
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

        assertTrue(LocalNotificationPresenter(context).dismissConversationMessages(account, group))

        val remaining = manager.activeNotifications.map { it.tag to it.id }
        assertEquals(listOf(other.tag to other.id), remaining)
    }

    @Test
    fun siblingDismissalClearsSiblingsAtOrBeforeBaselineButLeavesTheMessageCard() {
        val account = "account-a"
        val group = "group-a"
        val conversation = LocalNotificationFormatter.conversationDismissalKey(account, group)
        val reaction = LocalNotificationFormatter.reactionDismissalKey(account, group)
        val mention = LocalNotificationFormatter.mentionDismissalKey(account, group)
        listOf(conversation, reaction, mention).forEach { manager.notify(it.tag, it.id, notification()) }
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

    private fun notification(extras: Bundle? = null) =
        NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .apply { if (extras != null) addExtras(extras) }
            .build()

    private companion object {
        const val TEST_CHANNEL = "dismissal-test"
    }
}
