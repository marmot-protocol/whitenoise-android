package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Action identity reads precede the separate sibling inventory callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationActionIdentityReadTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Starts with independent platform and accepted-write state. */
    @Before
    fun setUp() {
        ConversationCardPostedRegistry.resetForTests()
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Removes only this fixture's tray state and channel. */
    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
        ConversationCardPostedRegistry.resetForTests()
    }

    /** Identity lookup leaves the card intact without invoking the callback that inspects its siblings. */
    @Test
    fun conversationCardIdentityReadDoesNotInspectSiblings() {
        val conversation = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
        val notification =
            NotificationCompat
                .Builder(context, TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentText("hello")
                .addExtras(
                    Bundle().apply {
                        putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, "msg-a")
                    },
                ).build()
        manager.notify(conversation.tag, conversation.id, notification)
        val siblingReads = AtomicInteger()
        val presenter =
            LocalNotificationPresenter(context, groupReconciliation = {}) { notificationManager ->
                siblingReads.incrementAndGet()
                notificationManager.activeNotifications
            }

        assertEquals("msg-a", presenter.conversationCardMessageIdHex(conversation.tag, conversation.id))
        assertEquals(0, siblingReads.get())
        assertEquals(1, manager.activeNotifications.size)
    }

    private companion object {
        const val TEST_CHANNEL = "action-identity-test"
    }
}
