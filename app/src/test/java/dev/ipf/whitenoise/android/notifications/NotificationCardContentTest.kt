package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationCardContentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sender =
        Person
            .Builder()
            .setName("Alice")
            .setKey("sender")
            .build()
    private val self =
        Person
            .Builder()
            .setName("Me")
            .setKey("self")
            .build()
    private val content = NotificationCardContent(context) { _, _ -> false to null }

    @Test
    fun unmatchedOrRedactedPayloadIsUnchangedWithoutReadingPrivatePictures() {
        val content = NotificationCardContent(context) { _, _ -> error("private picture must stay unread") }
        val ordinary = NotificationCompat.Builder(context, "messages").setContentText("ordinary").build()
        assertSame(ordinary, content.withCurrentContactPictures(ordinary))
        assertNull(content.renamedSenderNotification(ordinary, sender.key!!, "New name"))

        val redacted =
            messagingCard().apply {
                extras.putString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA, "opaque-scope")
                extras.putBoolean(EXTRA_CONTENT_REDACTED, true)
            }
        assertSame(redacted, content.withCurrentContactPictures(redacted))
        assertNull(content.renamedSenderNotification(redacted, sender.key!!, "New name"))
        assertNull(content.renamedSenderNotification(messagingCard(), "different-sender", "New name"))
    }

    @Test
    fun contactRefreshReadsTheStampedOpaqueScopeAndSkipsGroupSenderChoices() {
        val calls = mutableListOf<Pair<String, String>>()
        val content =
            NotificationCardContent(context) { account, contact ->
                calls += account to contact
                false to null
            }
        val direct = messagingCard().apply { extras.putString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA, "opaque-scope") }
        assertSame(direct, content.withCurrentContactPictures(direct))
        assertEquals(listOf("opaque-scope" to "sender"), calls)

        calls.clear()
        val group =
            messagingCard(group = true).apply {
                extras.putString(CONTACT_PICTURE_ACCOUNT_SCOPE_EXTRA, "opaque-scope")
            }
        assertSame(group, content.withCurrentContactPictures(group))
        assertEquals(emptyList<Pair<String, String>>(), calls)
    }

    @Test
    fun nicknameRewriteRetainsAttachmentsEntryIdentityAndActions() {
        val original = messagingCard()
        val rewritten = checkNotNull(content.renamedSenderNotification(original, "sender", "Nickname"))
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(rewritten))
        val message = style.messages.single()
        assertEquals("Nickname", message.person?.name)
        assertEquals("sender", message.person?.key)
        assertEquals("image/png", message.dataMimeType)
        assertEquals(Uri.parse("content://fixture/image"), message.dataUri)
        assertEquals("entry", message.extras.getString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX))
        assertEquals("Reply", rewritten.actions.single().title)
        assertEquals("body", message.text)
    }

    @Test
    fun preUpgradeSingleMessageRetainsItsEntryIdentityAndGroupTitleOnRename() {
        val groupTitle = "Test group"
        val original =
            NotificationCompat
                .Builder(context, "messages")
                .setContentTitle(context.getString(R.string.notification_sender_in_group, sender.name, groupTitle))
                .addExtras(
                    Bundle().apply {
                        putCharSequence(EXTRA_EXPANDED_SINGLE_MESSAGE_BODY, "legacy body")
                        putLong("dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_TIMESTAMP", 123L)
                        putBundle("dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_SENDER", sender.toBundle())
                        putCharSequence("dev.ipf.whitenoise.extra.EXPANDED_SINGLE_MESSAGE_GROUP_TITLE", groupTitle)
                        putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, "legacy-entry")
                    },
                ).build()
        val rewritten = checkNotNull(content.renamedSenderNotification(original, "sender", "Nickname"))
        assertFalse(rewritten.extras.containsKey(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX))
        val message = checkNotNull(content.expandedSingleMessage(rewritten.extras)).single()
        assertFalse(rewritten.extras.containsKey(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX))
        assertEquals("legacy body", message.text)
        assertEquals(123L, message.timestamp)
        assertEquals("Nickname", message.person?.name)
        assertEquals("sender", message.person?.key)
        assertEquals("legacy-entry", message.extras.getString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX))
        assertEquals(
            context.getString(R.string.notification_sender_in_group, "Nickname", groupTitle),
            rewritten.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
    }

    private fun messagingCard(group: Boolean = false): Notification {
        val message =
            NotificationCompat.MessagingStyle
                .Message("body", 10L, sender)
                .setData("image/png", Uri.parse("content://fixture/image"))
                .apply { extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, "entry") }
        return NotificationCompat
            .Builder(context, "messages")
            .setStyle(NotificationCompat.MessagingStyle(self).setGroupConversation(group).addMessage(message))
            .addAction(NotificationCompat.Action.Builder(0, "Reply", null).build())
            .build()
    }
}
