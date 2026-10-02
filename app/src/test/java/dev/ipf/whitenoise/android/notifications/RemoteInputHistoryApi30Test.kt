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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Android 11 SystemUI crashes with a ClassCastException when an app-posted card carries
 * RemoteInputHistoryItem extras after an inline reply, so the RemoteInput re-posts must not carry
 * them on API 30 while keeping the legacy text history, and must keep them on later releases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RemoteInputHistoryApi30Test {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val presenter = LocalNotificationPresenter(context, groupReconciliation = {})

    /** Grants notification permission and creates a clean test channel. */
    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Removes every posted card and the test channel. */
    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
    }

    /** A handled quick reply on API 30 re-posts the text history without the crashing item extra. */
    @Test
    fun handledReplyRepostOmitsHistoryItemsOnApi30() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertTrue(presenter.markDirectReplyHandled(TAG, ID, "On my way"))

        val extras = card().extras
        assertFalse(extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS))
        assertEquals(listOf("On my way"), legacyHistory(card()))
    }

    /** A handled quick reaction on API 30 re-posts the text history without the crashing item extra. */
    @Test
    fun handledReactionRepostOmitsHistoryItemsOnApi30() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertTrue(presenter.markReactionHandledIfSameGeneration(TAG, ID, "msg-a", "👍"))

        assertFalse(card().extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS))
        assertEquals(listOf("👍"), legacyHistory(card()))
    }

    /** A failed quick reply on API 30 re-posts the failure notice without the crashing item extra. */
    @Test
    fun failedReplyRepostOmitsHistoryItemsOnApi30() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertTrue(presenter.markDirectReplyFailed(TAG, ID, "msg-a", "Send failed"))

        assertFalse(card().extras.containsKey(EXTRA_REMOTE_INPUT_HISTORY_ITEMS))
        assertEquals(listOf("Send failed"), legacyHistory(card()))
    }

    /** Android 12+ reads the item extra safely and draws the history from it, so it stays. */
    @Test
    @Config(sdk = [31])
    fun handledReplyRepostKeepsHistoryItemsAfterApi30() {
        manager.notify(TAG, ID, repliableNotification("msg-a"))

        assertTrue(presenter.markDirectReplyHandled(TAG, ID, "On my way"))

        assertEquals(1, card().extras.getParcelableArray(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)?.size)
    }

    /** Returns the live card posted under the test (tag, id). */
    private fun card(): Notification = manager.activeNotifications.single { it.tag == TAG && it.id == ID }.notification

    /** Reads the legacy CharSequence RemoteInput history as strings. */
    private fun legacyHistory(notification: Notification): List<String>? =
        notification.extras
            .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
            ?.map(CharSequence::toString)

    /** Builds a MessagingStyle card with a RemoteInput reply action for [messageIdHex]. */
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
        const val TEST_CHANNEL = "remote-input-history-api30-test"
        const val TAG = "acct-a|group-1"
        const val ID = 7
    }
}
