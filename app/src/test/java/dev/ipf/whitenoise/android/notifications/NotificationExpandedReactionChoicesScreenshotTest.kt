package dev.ipf.whitenoise.android.notifications

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The expanded quick-reaction rail draws cropped built-in artwork and readable names in every theme and layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationExpandedReactionChoicesScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val choices = listOf(":marmot:", ":wn:", "👍", "❤️", "😂", ":party:")
    private val target =
        NotificationActionTarget(
            NotificationTarget("preview", "a".repeat(64), "b".repeat(64), NotificationTargetKind.MESSAGE),
            "card",
            7,
        )

    /** Restores the default quick reactions and platform font scale after each capture. */
    @After
    fun reset() {
        RecentEmojiPreferences.resetQuickReactions(context)
        RuntimeEnvironment.setFontScale(1f)
    }

    /** Marmot and White Noise fill their targets like the in-app rail; a choice without artwork reads by name. */
    @Test
    fun builtInChoicesLight() = capture("notification_reaction_choices_light")

    /** The cropped artwork and readable names keep their contrast on the dark template. */
    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun builtInChoicesDark() = capture("notification_reaction_choices_dark")

    /** RTL at 200% text mirrors the two rows and keeps each target readable. */
    @Test
    @Config(qualifiers = "ldrtl-w360dp-h800dp-mdpi")
    fun builtInChoicesLargeRtl() {
        RuntimeEnvironment.setFontScale(2f)
        capture("notification_reaction_choices_large_rtl")
    }

    /** Posts the saved choices through the production expanded layout and captures the platform template. */
    @Suppress("DEPRECATION")
    private fun capture(name: String) {
        RecentEmojiPreferences.saveQuickReactions(context, choices)
        val expanded = withExpandedReactionChoices(context, card("Lunch at the usual place?"), "card", 7)
        assertNotNull(expanded.bigContentView)
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            activity.setContentView(parent, ViewGroup.LayoutParams(WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT))
            val builder = Notification.Builder.recoverBuilder(activity, expanded)
            parent.addView(requireNotNull(builder.createBigContentView()).apply(activity, parent))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MAX_HEIGHT_PX, View.MeasureSpec.AT_MOST),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    /** A conversation card carrying the Reply action and fixed target the expanded rail requires. */
    private fun card(body: String): Notification {
        val sender = Person.Builder().setName("Fixture contact").build()
        val style =
            NotificationCompat
                .MessagingStyle(Person.Builder().setName("Fixture account").build())
                .addMessage(body, 1L, sender)
        return NotificationCompat
            .Builder(context, "fixture")
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            // A fixed header keeps the baseline independent of the capture date.
            .setShowWhen(false)
            .setStyle(style)
            .addAction(
                NotificationCompat.Action
                    .Builder(
                        R.drawable.ic_stat_whitenoise,
                        context.getString(R.string.reply),
                        checkNotNull(fixedNotificationReactionPendingIntent(context, target, ":marmot:", choices)),
                    ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            ).addExtras(notificationFixedReactionTargetExtras(target))
            .addExtras(
                Bundle().apply {
                    val messageId = target.target.messageIdHex
                    putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, messageId)
                },
            ).build()
    }

    private companion object {
        const val WIDTH_PX = 360
        const val MAX_HEIGHT_PX = 1200
    }
}
