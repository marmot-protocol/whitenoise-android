package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
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

/** Restart simulations keep durable preferences/live OS cards, with no surviving work from the old process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreviewRestartTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setup() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            NotificationGroupReconciler.shared(context).close()
            manager.cancelAll()
            context
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
        }

    @Test
    fun restartKeepsStackedHistoryAndReplyActions() =
        runBlocking {
            val old = presenter(context)
            assertTrue(old.show(update(), shortNpub = { "sender" }))
            val restarted = presenter(freshProcess())
            assertTrue(
                restarted.show(
                    update().copy(messageIdHex = "second", previewText = "Second line"),
                    shortNpub = { "sender" },
                ),
            )
            val card = manager.activeNotifications.single().notification
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card)!!.messages
            assertEquals(listOf("Private text", "Second line"), messages.map { it.text.toString() })
            assertRichReply(card)
        }

    @Test
    fun restartKeepsNicknameCorrectionRich() =
        runBlocking {
            assertTrue(presenter(context).show(update(), shortNpub = { "sender" }))
            assertEquals(1, presenter(freshProcess()).refreshContactSenderName("account-a", "sender", "New nickname"))
            val card = manager.activeNotifications.single().notification
            assertRichReply(card)
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card)!!.messages
            assertEquals("New nickname", messages.last().person!!.name)
            assertEquals("Private text", messages.last().text)
        }

    @Test
    fun restartKeepsHandledAndFailedReplyCopiesRich() =
        runBlocking {
            assertTrue(presenter(context).show(update(), shortNpub = { "sender" }))
            val target = manager.activeNotifications.single()
            val restarted = presenter(freshProcess())
            assertTrue(restarted.markDirectReplyHandled(target.tag, target.id, "Reply draft"))
            assertRichReply(manager.activeNotifications.single().notification)
            assertTrue(restarted.markDirectReplyFailed(target.tag, target.id, "msg-a", "Retry sending"))
            val failed = manager.activeNotifications.single().notification
            assertRichReply(failed)
            assertEquals(listOf("Retry sending"), remoteInputHistory(failed))
        }

    @Test
    fun laterPrivacyEpochRejectsOldLiveContentAfterRestart() =
        runBlocking {
            assertTrue(presenter(context).show(update(), shortNpub = { "sender" }))
            val old = manager.activeNotifications.single().notification
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
            val process = freshProcess()
            assertFalse(NotificationPreviewPreferences.canRetainPreview(process, old))
            assertFalse(NotificationPreviewPreferences.canExpose(process, old, old))
        }

    @Test
    fun persistedOnAllowsFirstSilentRichPostAfterRestart() =
        runBlocking {
            assertTrue(presenter(freshProcess()).show(update(), silentUpdate = true, shortNpub = { "sender" }))
            assertRichReply(manager.activeNotifications.single().notification)
        }

    private fun freshProcess(): Context =
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
        }

    private fun presenter(context: Context): LocalNotificationPresenter {
        val presenter = LocalNotificationPresenter(context, enrichmentLauncher = {}, groupReconciliation = {})
        presenter.ensureChannels()
        return presenter
    }

    private fun assertRichReply(card: Notification) {
        assertFalse(card.extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
        assertTrue(card.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() })
        assertTrue(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card) != null)
    }

    private fun remoteInputHistory(notification: Notification): List<String>? {
        val legacy = notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (legacy != null) return legacy.map(CharSequence::toString)
        return notification.extras.getParcelableArray("android.remoteInputHistoryItems")?.map { item ->
            item.javaClass
                .getMethod("getText")
                .invoke(item)
                ?.toString()
                .orEmpty()
        }
    }

    private fun update() =
        NotificationUpdateFfi(
            notificationKey = "key",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "Private group",
            isDm = false,
            isMention = false,
            messageIdHex = "msg-a",
            sender = NotificationUserFfi("sender", "Private sender", null),
            receiver = NotificationUserFfi("self", "Private self", null),
            previewText = "Private text",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1000L,
            isFromSelf = false,
        )
}
