package dev.ipf.whitenoise.android.notifications

import android.app.Activity
import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.descendants
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Android RemoteViews for the generic summary and redacted lock-screen presentation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationGroupSummaryScreenshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun multiAccountSummaryHasOnlyGenericCount() =
        capture(
            summary(4),
            "notification_group_summary_light",
            "4 notifications",
        )

    @Test
    @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun oneChildSummaryStaysCompactInDarkMode() =
        capture(
            summary(1),
            "notification_group_summary_single_dark",
            "1 notification",
        )

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h800dp-night-mdpi")
    fun largeTextRtlSummaryUsesTheNativeTemplate() {
        RuntimeEnvironment.setFontScale(2f)
        assertTrue(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        val expected = context.resources.getQuantityString(R.plurals.notification_group_count, 24, 24)
        capture(summary(24), "notification_group_summary_large_rtl", expected)
    }

    @Test
    fun lockScreenPublicVersionContainsNoChildIdentity() {
        val notification = summary(4).publicVersion
        capture(
            notification,
            "notification_group_summary_redacted",
            context.getString(R.string.notification_hidden_content),
        )
    }

    private fun summary(count: Int): Notification {
        NotificationChannels.ensureChannels(context)
        return UserEventNotificationGroup.summary(
            context,
            List(count) {
                NotificationGroupChild("synthetic-account|synthetic-group-$it", 0, "synthetic-generation-$it")
            },
        )
    }

    private fun capture(
        notification: Notification,
        name: String,
        expectedText: String,
    ) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            parent.layoutDirection = activity.resources.configuration.layoutDirection
            activity.setContentView(parent, ViewGroup.LayoutParams(360, ViewGroup.LayoutParams.WRAP_CONTENT))
            val remoteViews = Notification.Builder.recoverBuilder(activity, notification).createContentView()
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
            assertTrue(texts.contains(expectedText))
            assertFalse(
                texts.any {
                    it.contains("synthetic-account") ||
                        it.contains("synthetic-group") ||
                        it.contains("synthetic-generation")
                },
            )
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }
}
