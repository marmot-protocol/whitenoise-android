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
class AccountNotificationCleanupTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
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

    /** Sign-out and wipe cancel the departing account's cards on shared channels and keep other accounts' cards. */
    @Test
    fun accountCleanupCancelsTheAccountsCardsOnly() {
        val account = "account-a"
        val message = LocalNotificationFormatter.conversationDismissalKey(account, "group-a")
        val mention = LocalNotificationFormatter.mentionDismissalKey(account, "group-b")
        manager.notify(message.tag, message.id, notification())
        manager.notify(mention.tag, mention.id, notification())
        manager.notify(
            "invite-a",
            42,
            notification(
                Bundle().apply {
                    putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, account)
                    putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, "group-c")
                },
            ),
        )
        val other = LocalNotificationFormatter.conversationDismissalKey("account-b", "group-a")
        manager.notify(other.tag, other.id, notification())
        var reconciled = false

        LocalNotificationPresenter(context, groupReconciliation = { reconciled = true })
            .clearRevokedConversationShortcutsForAccount(
                accountRef = account,
                includeUnscopedLegacy = false,
                knownGroupIds = setOf("group-a", "group-b", "group-c"),
            )

        assertEquals(listOf(other.tag to other.id), manager.activeNotifications.map { it.tag to it.id })
        assertTrue(reconciled)
    }

    private fun notification(extras: Bundle? = null) =
        NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test")
            .apply { if (extras != null) addExtras(extras) }
            .build()

    private companion object {
        const val TEST_CHANNEL = "account-cleanup-test"
    }
}
