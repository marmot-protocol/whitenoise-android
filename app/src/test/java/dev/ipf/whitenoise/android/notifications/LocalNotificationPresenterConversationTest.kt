package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private var publishedShortcut: ShortcutInfoCompat? = null
    private lateinit var presenter: LocalNotificationPresenter

    @Before
    fun setUp() {
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        publishedShortcut = null
        presenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { shortcut ->
                    publishedShortcut = shortcut
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun groupMessagePostsOnTheGroupConversationChannelWithTheConversationShortcut() {
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
        val shortcut = checkNotNull(publishedShortcut)
        assertEquals(shortcutId, shortcut.id)
        assertNotNull(shortcut.icon)
        assertEquals(IconCompat.TYPE_BITMAP, shortcut.icon!!.type)
        assertEquals(
            "message",
            notification.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX),
        )
    }

    @Test
    fun mentionPostsOnTheMentionConversationChannelForTheSameConversation() {
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

    @Test
    fun conversationShortcutUsesPlainBitmapWithoutAdaptiveSafeZoneCrop() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val icon = notificationConversationIcon(title = "Alice", seed = "conversation-a", avatarBitmap = bitmap)

        assertEquals(IconCompat.TYPE_BITMAP, icon.type)
    }

    @Test
    fun avatarlessConversationGetsDeterministicDistinctMonogramBitmap() {
        assertEquals("AB", notificationAvatarInitials(" Alice   Baker "))
        assertEquals("AB", notificationAvatarInitials("Alice\u00a0Baker"))
        assertEquals("A", notificationAvatarInitials("Alice"))
        assertEquals("?", notificationAvatarInitials(" \u202e "))
        assertEquals(notificationMonogramBackgroundColor("conversation-a"), notificationMonogramBackgroundColor("conversation-a"))
        assertNotEquals(notificationMonogramBackgroundColor("conversation-a"), notificationMonogramBackgroundColor("conversation-b"))

        val bitmap = notificationMonogramBitmap(title = "Alice Baker", seed = "conversation-a", sizePx = 32)
        val icon = notificationConversationIcon(title = "Alice Baker", seed = "conversation-a", avatarBitmap = null)

        assertEquals(32, bitmap.width)
        assertEquals(32, bitmap.height)
        assertEquals(IconCompat.TYPE_BITMAP, icon.type)
    }

    @Test
    fun monogramBackgroundMaintainsReadableContrastWithWhiteInitials() {
        val brightestHueSeed = "gk" // Java hash 3300 -> hue 60 (yellow).

        assertTrue(
            ColorUtils.calculateContrast(
                Color.WHITE,
                notificationMonogramBackgroundColor(brightestHueSeed),
            ) >= 4.5,
        )
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
