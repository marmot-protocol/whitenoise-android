package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises real notification publication and the platform's rendered one-sentence templates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalNotificationRemovalScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Isolates synthetic cards and grants only the test application's notification permission. */
    @Before
    fun setUp() {
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The ordinary removal keeps its title, membership route, and no duplicate text or actions. */
    @Test
    fun namedRemovalUsesOneSentence() {
        val notification = post()
        assertRemoval(notification, "You were removed from Launch")
        capture(notification, "notification_removal_one_sentence_light")
    }

    /** An unnamed group's fallback remains readable without an empty expanded body. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun unnamedRemovalUsesOneSentenceInDarkMode() {
        val notification = post(groupName = null)
        assertRemoval(notification, "You were removed from a group")
        capture(notification, "notification_removal_unnamed_dark")
    }

    /** Large RTL presentation exercises the platform template with a longer group title. */
    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-night-mdpi")
    fun longGroupRemovalUsesOneSentenceWithLargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        val notification = post(groupName = "Launch planning and release coordination")
        assertRemoval(notification, "You were removed from Launch planning and release coordination")
        capture(notification, "notification_removal_large_rtl", expanded = true)
    }

    /** Removing event detail must not suppress the generic body required by privacy redaction. */
    @Test
    fun redactedRemovalRetainsOnlyGenericContent() {
        val notification = post(redacted = true)
        assertEquals(
            context.getString(R.string.app_name),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        val hidden = context.getString(R.string.notification_hidden_content)
        assertEquals(hidden, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(hidden, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    /** Neighboring admin events retain their informative body and expandable text. */
    @Test
    fun adminChangeRetainsItsBody() {
        val notification = post(trigger = NotificationTriggerFfi.MADE_ADMIN)
        assertEquals("Alice made you an admin", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("Alice made you an admin", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
    }

    /** Checks the published card and its unchanged account/group-scoped navigation contract. */
    private fun assertRemoval(
        notification: Notification,
        title: String,
    ) {
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals("", notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals(NotificationChannelSpec.GROUP_MEMBERSHIP.id, notification.channelId)
        assertEquals(Notification.CATEGORY_EVENT, notification.category)
        val target =
            NotificationNavigation.parse(
                shadowOf(notification.contentIntent).savedIntent,
                isTrustedTapToken = NotificationTapTokens.create(context)::isValid,
            )
        assertEquals("removal-account", target?.accountRef)
        assertEquals("removal-group", target?.groupIdHex)
        assertEquals(NotificationTargetKind.CHAT_LIST, target?.kind)
    }

    /** Renders actual platform RemoteViews; no Compose approximation of SystemUI is used. */
    @Suppress("DEPRECATION") // Platform template inflation is intentional screenshot-only coverage.
    private fun capture(notification: Notification, name: String, expanded: Boolean = false) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent, ViewGroup.LayoutParams(360, ViewGroup.LayoutParams.WRAP_CONTENT))
            val builder = Notification.Builder.recoverBuilder(activity, notification)
            val remoteViews =
                if (expanded) checkNotNull(builder.createBigContentView()) else builder.createContentView()
            parent.addView(remoteViews.apply(activity, parent))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            val texts =
                parent.descendants
                    .filterIsInstance<TextView>()
                    .filter { it.isShown }
                    .map { it.text.toString() }
                    .toList()
            assertEquals(1, texts.count { it.startsWith("You were removed from") })
            if (expanded) {
                assertTrue(texts.contains(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()))
            }
            assertFalse(texts.any { it.contains("Alice removed you") || it.contains("Someone removed you") })
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    /** Posts one synthetic update through the production presenter without account or network setup. */
    private fun post(
        groupName: String? = "Launch",
        redacted: Boolean = false,
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.REMOVED_FROM_GROUP,
    ): Notification {
        val user = NotificationUserFfi("1".repeat(64), "Alice", null)
        val update =
            NotificationUpdateFfi(
                notificationKey = "removal-event",
                conversationKey = "removal-conversation",
                trigger = trigger,
                trafficClass = NotificationTrafficClassFfi.STANDARD,
                accountRef = "removal-account",
                accountIdHex = "2".repeat(64),
                groupIdHex = "removal-group",
                groupName = groupName,
                isDm = false,
                messageIdHex = "3".repeat(64),
                sender = user,
                receiver = user.copy(accountIdHex = "2".repeat(64), displayName = "Me"),
                previewText = "Someone removed you",
                timestampMs = 0L,
                isFromSelf = false,
                isMention = false,
                reactionEmoji = null,
                reactedToPreview = null,
            )
        val presenter = LocalNotificationPresenter(context, nowMillis = { 0L })
        presenter.ensureChannels()
        runBlocking {
            assertTrue(
                presenter.show(
                    update,
                    previewTextOverride =
                        if (trigger == NotificationTriggerFfi.MADE_ADMIN) {
                            "Alice made you an admin"
                        } else {
                            "Alice removed you"
                        },
                    redactContent = redacted,
                    shortNpub = { "npub1test" },
                ),
            )
        }
        val posted = manager.activeNotifications.single()
        assertEquals("group-membership|removal-account|removal-group", posted.tag)
        assertEquals(LocalNotificationFormatter.GROUP_MEMBERSHIP_NOTIFICATION_ID, posted.id)
        return posted.notification
    }
}
