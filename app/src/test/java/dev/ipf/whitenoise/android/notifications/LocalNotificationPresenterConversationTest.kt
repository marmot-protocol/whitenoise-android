package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class LocalNotificationPresenterConversationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun groupMessagePostsOnTheGroupConversationChannelWithTheConversationShortcut() {
        val presenter = LocalNotificationPresenter(context)
        presenter.ensureChannels()

        val posted =
            runBlocking {
                presenter.show(update(isMention = false), previewTextOverride = "hi")
            }

        assertTrue(posted)
        val shortcutId = conversationShortcutId("account-a", "group-a")
        val notification = manager.activeNotifications.single().notification
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.GROUP_MESSAGES.id, shortcutId!!),
            notification.channelId,
        )
        assertEquals(shortcutId, notification.shortcutId)
    }

    @Test
    fun mentionPostsOnTheMentionConversationChannelForTheSameConversation() {
        val presenter = LocalNotificationPresenter(context)
        presenter.ensureChannels()

        runBlocking { presenter.show(update(isMention = true), previewTextOverride = "hi") }

        val shortcutId = conversationShortcutId("account-a", "group-a")
        val notification = manager.activeNotifications.single().notification
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.MENTIONS.id, shortcutId!!),
            notification.channelId,
        )
        assertEquals(shortcutId, notification.shortcutId)
    }

    @Test
    fun senderPersonUsesTheResolvedAvatarBitmap() {
        val content = LocalNotificationFormatter.content(update(isMention = false), context)!!
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        assertNotNull(notificationSenderPerson(content, bitmap).icon)
        assertNull(notificationSenderPerson(content, null).icon)
    }

    private fun update(isMention: Boolean) =
        NotificationUpdateFfi(
            notificationKey = "key",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "General",
            isDm = false,
            isMention = isMention,
            messageIdHex = "message",
            sender = user(displayName = "Alice"),
            receiver = user(accountIdHex = "self", displayName = "Me"),
            previewText = "hi",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1234,
            isFromSelf = false,
        )

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )
}
