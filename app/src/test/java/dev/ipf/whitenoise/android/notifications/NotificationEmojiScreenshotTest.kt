package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The actual platform conversation template, not a Compose approximation of the notification. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = "w360dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationEmojiScreenshotTest {
    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        pruneNotificationEmojiArtwork(context, emptyArray())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun localArtworkUsesTheStandardConversationTemplate() =
        runBlocking {
            val notification = post("Hello :wn: :marmot:")
            val messages = styleOf(notification).messages
            assertNotNull(messages.first().dataUri)
            assertEquals("Hello :wn: :marmot:", messages.last().text.toString())
            capture(notification, "notification_emoji_artwork_light")
        }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun missingArtworkRetainsTheOriginalText() =
        runBlocking {
            val notification = post("Hello :missing:")
            val messages = styleOf(notification).messages
            assertTrue(messages.all { it.dataUri == null })
            capture(notification, "notification_emoji_missing_dark")
        }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun artworkRetainsContrastInTheDarkTemplate() =
        runBlocking {
            capture(post("Hello :wn: :marmot:"), "notification_emoji_artwork_dark")
        }

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-night-mdpi")
    fun loadedArtworkKeepsTextAtLargeRtlFont() =
        runBlocking {
            RuntimeEnvironment.setFontScale(2.0f)
            capture(post("Hello :wn: :marmot:"), "notification_emoji_artwork_large_rtl")
        }

    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-night-mdpi")
    fun privacyRedactionContainsNoArtworkAtLargeFont() =
        runBlocking {
            RuntimeEnvironment.setFontScale(2.0f)
            val notification =
                post(
                    "Hello :wn:",
                    redacted = true,
                )
            val messages = styleOf(notification).messages
            assertTrue(messages.all { it.dataUri == null && ":wn:" !in it.text.toString() })
            capture(notification, "notification_emoji_redacted_large_rtl")
        }

    @Suppress("DEPRECATION") // Inflate actual platform RemoteViews only for deterministic visual evidence.
    private fun capture(
        notification: Notification,
        name: String,
    ) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent, ViewGroup.LayoutParams(360, ViewGroup.LayoutParams.WRAP_CONTENT))
            val builder = Notification.Builder.recoverBuilder(activity, notification)
            parent.addView(requireNotNull(builder.createBigContentView()).apply(activity, parent))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    private suspend fun post(
        text: String,
        redacted: Boolean = false,
    ): Notification {
        val user = NotificationUserFfi("1".repeat(64), "Alice", null)
        val update =
            NotificationUpdateFfi(
                notificationKey = "emoji-screenshot",
                conversationKey = "conversation",
                trigger = NotificationTriggerFfi.NEW_MESSAGE,
                trafficClass = NotificationTrafficClassFfi.STANDARD,
                accountRef = "account",
                accountIdHex = "2".repeat(64),
                groupIdHex = "group",
                groupName = "Team",
                isDm = false,
                messageIdHex = "3".repeat(64),
                sender = user,
                receiver =
                    user.copy(
                        accountIdHex = "2".repeat(64),
                        displayName = "Me",
                    ),
                previewText = text,
                timestampMs = 0L,
                isFromSelf = false,
                isMention = false,
                reactionEmoji = null,
                reactedToPreview = null,
            )
        val artifact = if (redacted) null else notificationEmojiArtwork(context, text)
        val presenter =
            LocalNotificationPresenter(
                context,
                nowMillis = { 0L },
                groupReconciliation = {},
                enrichmentLauncher = {},
                emojiArtworkPreparer = { _, _ -> artifact },
            )
        presenter.ensureChannels()
        check(
            presenter.show(
                update,
                redactContent = redacted,
                shortNpub = { "npub1fixture" },
            ),
        )
        return manager.activeNotifications.single().notification
    }

    private fun styleOf(notification: android.app.Notification): NotificationCompat.MessagingStyle =
        requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
}
