package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the Jump to date calendar over a loaded grid, with only loaded days available. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SharedMediaDateJumpScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The calendar opens on the viewed day; the other loaded day in that month stays available. */
    @Test
    fun calendarDark() = capture("dark", dark = true)

    /** The calendar stays readable in right-to-left layout at 200% text. */
    @Test
    fun calendarLightRtlLargeText() = capture("light_rtl_200", dark = false, rtl = true, fontScale = 2f)

    private fun capture(
        name: String,
        dark: Boolean,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val rows =
            (1..7).map { entry(it, NOV) } + (8..10).map { entry(it, NOV_10) } + (11..14).map { entry(it, SEP) } +
                (15..18).map { entry(it, MAY_2022) }
        val state = GroupAttachmentState(entries = rows, loading = false, initialized = true, hasMore = true)
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) { DateJumpHarness(state) }
            }
        }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/shared_media_date_jump_$name.png")
    }
}
