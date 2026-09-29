package dev.ipf.whitenoise.android.audio

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The three actions must fit the notification's compact 48dp content, without requiring expansion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ConversationDictationNotificationScreenshotTest {
    @Test
    fun compactActionsLight() {
        capture("dictation_notification_compact_light")
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-mdpi-night")
    fun compactActionsDark() {
        capture("dictation_notification_compact_dark")
    }

    @Test
    @Config(qualifiers = "ar-rEG-w360dp-h780dp-mdpi")
    fun compactActionsRtl() {
        capture("dictation_notification_compact_rtl", rtl = true)
    }

    private fun capture(
        name: String,
        rtl: Boolean = false,
    ) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent, ViewGroup.LayoutParams(360, ViewGroup.LayoutParams.WRAP_CONTENT))
            val content = RemoteViews(activity.packageName, R.layout.notification_dictation_compact)
                .apply(activity, parent)
            if (rtl) content.layoutDirection = View.LAYOUT_DIRECTION_RTL
            parent.addView(content)
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
            val buttons =
                listOf(
                    R.id.dictation_notification_cancel,
                    R.id.dictation_notification_paste,
                    R.id.dictation_notification_send,
                ).map { parent.findViewById<Button>(it) }
            assertEquals(listOf("Cancel", "Paste", "Send"), buttons.map { it.text.toString() })
            assertTrue(buttons.all { it.width > 0 && it.height > 0 && it.isShown })
            parent.captureRoboImage("src/test/snapshots/$name.png")
        }
    }
}
