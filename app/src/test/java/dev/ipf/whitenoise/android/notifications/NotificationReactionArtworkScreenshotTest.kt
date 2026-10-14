package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Collapsed and expanded Android templates verify visible reaction artwork and accessible fallback states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationReactionArtworkScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Establish an empty tray with permission and the default preview policy. */
    @Before
    fun setUp() {
        manager.cancelAll()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Marmot appears in the decorated inline template, with reaction text kept in both sizes. */
    @Test
    fun marmotReactionInLightTheme() =
        runBlocking {
            val card = post(":marmot:")
            assertNotNull(card.contentView)
            capture(card, "notification_reaction_marmot_light")
        }

    /** Another bundled custom emoji has the same hierarchy in dark mode. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun whiteNoiseReactionInDarkTheme() = runBlocking { capture(post(":wn:"), "notification_reaction_wn_dark") }

    /** A card posted in light mode re-inflates with dark text colors without replacing the payload. */
    @Test
    fun postedLightCardAfterDarkThemeSwitch() =
        runBlocking {
            val card = post(":marmot:")
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-night-mdpi")
            capture(card, "notification_reaction_posted_light_to_dark")
        }

    /** The inverse theme switch restores the light palette for the same posted artwork card. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun postedDarkCardAfterLightThemeSwitch() =
        runBlocking {
            val card = post(":wn:")
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-notnight-mdpi")
            capture(card, "notification_reaction_posted_dark_to_light")
        }

    /** Wide templates retain bounded preview wrapping while metadata keeps the complete long target. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun longReactionInWideTemplate() =
        runBlocking {
            capture(
                post(":marmot:", preview = LONG_TARGET),
                "notification_reaction_wide_bounded_preview",
                width = 800,
            )
        }

    /** Small hosts and large text show the conservative bitmap scaling and ellipsis tradeoff. */
    @Test
    @Config(qualifiers = "w240dp-h800dp-mdpi")
    fun longReactionInNarrowLargeFontTemplate() =
        runBlocking {
            RuntimeEnvironment.setFontScale(2f)
            capture(
                post(":marmot:", preview = LONG_TARGET),
                "notification_reaction_narrow_large_bounded_preview",
                width = 240,
            )
        }

    /** The large RTL inline card retains readable reaction context. */
    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-night-mdpi")
    fun marmotReactionAtLargeRtlFont() =
        runBlocking {
            RuntimeEnvironment.setFontScale(2.0f)
            capture(post(":marmot:"), "notification_reaction_marmot_large_rtl")
        }

    /** Unavailable custom artwork keeps the exact shortcode instead of substituting another image. */
    @Test
    fun unavailableCustomArtwork() =
        runBlocking {
            val card = post(":party:")
            assertNull(card.getLargeIcon())
            capture(card, "notification_reaction_unavailable_light")
        }

    /** Standard Unicode stays readable in the text template. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun unicodeReactionInDarkTheme() = runBlocking { capture(post("👍"), "notification_reaction_unicode_dark") }

    /** A rejected picture still delivers the accessible reaction through the native text template. */
    @Test
    fun rejectedPictureShowsTextFallback() =
        runBlocking {
            val card = post(":marmot:", rejectPicture = true)
            assertNull(card.getLargeIcon())
            capture(card, "notification_reaction_rejected_picture_light")
        }

    /** Generic hidden cards contain neither reaction artwork nor private names at large font. */
    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-night-mdpi")
    fun hiddenReactionAtLargeRtlFont() =
        runBlocking {
            RuntimeEnvironment.setFontScale(2.0f)
            val card = post(":marmot:", redacted = true)
            assertNull(card.getLargeIcon())
            capture(card, "notification_reaction_hidden_large_rtl")
        }

    /** Capture real platform RemoteViews, showing collapsed then expanded forms. */
    @Suppress("DEPRECATION")
    private fun capture(
        notification: Notification,
        name: String,
        width: Int = 360,
    ) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            activity.setContentView(parent, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
            val builder = Notification.Builder.recoverBuilder(activity, notification)
            parent.addView(builder.createContentView().apply(activity, parent))
            parent.addView(requireNotNull(builder.createBigContentView()).apply(activity, parent))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.AT_MOST),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    /** Exercise the presenter with the real artwork resolver and a fixed presentation time. */
    private suspend fun post(
        emoji: String,
        redacted: Boolean = false,
        rejectPicture: Boolean = false,
        preview: String? = null,
    ): Notification {
        val presenter =
            LocalNotificationPresenter(
                context,
                nowMillis = { 0L },
                groupReconciliation = {},
                enrichmentLauncher = {},
                emojiArtworkTimeoutMs = 5_000L,
                notificationPoster = { target, tag, id, card ->
                    if (rejectPicture && card.contentView != null) {
                        throw IllegalArgumentException("picture rejected")
                    }
                    target.notify(tag, id, card)
                },
            )
        presenter.ensureChannels()
        check(
            presenter.show(
                reactionArtworkUpdate(emoji).let {
                    when {
                        preview != null -> it.copy(reactedToPreview = preview)
                        emoji == "👍" || emoji == ":party:" -> it.copy(reactedToPreview = "Hello")
                        else -> it
                    }
                },
                redactContent = redacted,
                shortNpub = { "npub1fixture" },
            ),
        )
        return manager.activeNotifications.single().notification
    }

    private companion object {
        const val LONG_TARGET =
            "Hello :wn: please review the document and share your notes about the next release, " +
                "including the updated artwork, privacy settings and notification presentation on different displays."
    }
}
