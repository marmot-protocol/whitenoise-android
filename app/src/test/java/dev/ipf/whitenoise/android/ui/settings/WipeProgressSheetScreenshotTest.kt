package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The uncancellable wipe sheet tells people to keep White Noise open while it leaves their chats. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class WipeProgressSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The keep-open note sits under the three stages. */
    @Test
    fun keepOpenNoteLight() = capture(dark = false, largeRtl = false, name = "light")

    /** The note wraps under large dark RTL text without hiding a stage. */
    @Test
    fun keepOpenNoteDarkLargeRtl() = capture(dark = true, largeRtl = true, name = "dark_large_rtl")

    /** Renders the sheet on a paused clock, since its loading indicators never settle, and records the baseline. */
    private fun capture(
        dark: Boolean,
        largeRtl: Boolean,
        name: String,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 1.5f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WipeProgressSheet()
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SHEET_SETTLE_MILLIS)
        composeRule
            .onNodeWithText("This can take up to a minute. Keep White Noise open until it finishes.")
            .assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/wipe_progress_keep_open_$name.png")
    }

    private companion object {
        const val SHEET_SETTLE_MILLIS = 1_000L
    }
}
