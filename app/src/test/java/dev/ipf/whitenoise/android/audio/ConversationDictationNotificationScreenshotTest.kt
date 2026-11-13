package dev.ipf.whitenoise.android.audio

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the framework-expanded notification template with the production dictation actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ConversationDictationNotificationScreenshotTest {
    @Test
    fun nativeActionsLight() {
        capture("dictation_notification_native_light")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-night-mdpi")
    fun nativeActionsDark() {
        capture("dictation_notification_native_dark")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w360dp-h780dp-mdpi")
    fun nativeActionsRtl() {
        capture("dictation_notification_native_rtl", rtl = true)
    }

    private fun capture(name: String, rtl: Boolean = false) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent, ViewGroup.LayoutParams(360, ViewGroup.LayoutParams.WRAP_CONTENT))
            val harness = DictationForegroundTestHost()
            val notification =
                buildConversationDictationNotification(activity, harness.conversationDictation) { action, _ ->
                    PendingIntent.getActivity(
                        activity,
                        0,
                        Intent(activity, Activity::class.java).setAction(action),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            assertNull(notification.contentView)
            assertEquals(3, notification.actions.orEmpty().size)
            val expanded = Notification.Builder.recoverBuilder(activity, notification).createBigContentView()
            assertNotNull(expanded)
            val content = requireNotNull(expanded).apply(activity, parent)
            if (rtl) parent.layoutDirection = View.LAYOUT_DIRECTION_RTL
            parent.addView(content)
            val width = activity.resources.displayMetrics.widthPixels
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }
}
