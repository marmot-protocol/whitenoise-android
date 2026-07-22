package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationReplyFailureTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val presenter = LocalNotificationPresenter(context)

    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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
    fun failedReplyRepostKeepsCardRepliableAndStampsFailureNotice() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertTrue(presenter.markDirectReplyFailed(TAG, ID, "msg-a", "Send failed"))

        val active = manager.activeNotifications.single { it.tag == TAG && it.id == ID }
        assertEquals(listOf("Send failed"), remoteInputHistoryTexts(active.notification))
        // The reply action (and its RemoteInput) must survive the re-post so
        // the user can retype instead of finding a dead card.
        assertEquals(
            1,
            active.notification.actions
                .orEmpty()
                .size,
        )
        assertEquals("msg-a", conversationCardMessageIdHex(active.notification))
    }

    @Test
    fun failedReplyRepostSkipsNewerGenerationCard() {
        manager.notify(TAG, ID, repliableNotification("msg-b"))

        assertFalse(presenter.markDirectReplyFailed(TAG, ID, "msg-a", "Send failed"))

        val active = manager.activeNotifications.single { it.tag == TAG && it.id == ID }
        assertNull(remoteInputHistoryTexts(active.notification))
    }

    @Test
    fun failedReplyRepostIsNoOpWithoutALiveCard() {
        assertFalse(presenter.markDirectReplyFailed(TAG, ID, "msg-a", "Send failed"))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun failedReplyRepostIsNoOpWithoutARepliedMessageId() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertFalse(presenter.markDirectReplyFailed(TAG, ID, repliedMessageIdHex = null, failureNotice = "Send failed"))

        val active = manager.activeNotifications.single { it.tag == TAG && it.id == ID }
        assertNull(remoteInputHistoryTexts(active.notification))
    }

    /**
     * Reads back the RemoteInput history regardless of SDK storage format:
     * older framework builders store a CharSequence[] under
     * EXTRA_REMOTE_INPUT_HISTORY; modern ones store RemoteInputHistoryItem
     * parcelables under "android.remoteInputHistoryItems" and null the legacy
     * key. The item class is hidden API, so its text is read reflectively.
     */
    private fun remoteInputHistoryTexts(notification: Notification): List<String>? {
        val legacy = notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (legacy != null) return legacy.map(CharSequence::toString)
        val items = notification.extras.getParcelableArray("android.remoteInputHistoryItems")
        return items?.map { item ->
            item.javaClass
                .getMethod("getText")
                .invoke(item)
                ?.toString()
                .orEmpty()
        }
    }

    private fun repliableNotification(messageIdHex: String): Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        style.addMessage("hello", 1_000L, Person.Builder().setName("Alice").build())
        val replyAction =
            NotificationCompat.Action
                .Builder(
                    android.R.drawable.ic_menu_send,
                    "Reply",
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent("test.reply"),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).addRemoteInput(RemoteInput.Builder("key_text").build())
                .build()
        return NotificationCompat
            .Builder(context, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .addAction(replyAction)
            .addExtras(
                Bundle().apply {
                    putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, messageIdHex)
                },
            ).build()
    }

    private companion object {
        const val TEST_CHANNEL = "reply-failure-test"
        const val TAG = "acct-a|group-1"
        const val ID = 7
    }
}
