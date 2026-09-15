package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.DonateScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Donate with a prominent website action below the heart and support copy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class DonateScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun donateLight() = capture("donate_light", darkTheme = false)

    /** Dark theme. */
    @Test
    fun donateDark() = capture("donate_dark", darkTheme = true)

    /** AMOLED: the donation action stays visible on the black canvas. */
    @Test
    fun donateAmoled() = capture("donate_amoled", darkTheme = true, amoled = true)

    /** Long translated text and enlarged fonts retain a reachable call to action. */
    @Test
    @Config(qualifiers = "de-w360dp-h900dp-mdpi")
    fun donateGermanLarge() = capture("donate_german_large", darkTheme = false, fontScale = 2f)

    /** A refused browser hand-off keeps the call to action and reports a recoverable error. */
    @Test
    fun donateOpenFailed() = capture("donate_open_failed", darkTheme = false, openFailed = true)

    /** Error copy remains readable with enlarged text on a dark canvas. */
    @Test
    fun donateOpenFailedLargeDark() =
        capture(
            "donate_open_failed_large_dark",
            darkTheme = true,
            fontScale = 1.6f,
            openFailed = true,
        )

    /** Renders the screen and records the window. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        openFailed: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String): Unit = throw IllegalArgumentException("No browser")
                    },
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    DonateScreen(onBack = {})
                }
            }
        }
        if (openFailed) composeRule.onNodeWithTag("donate.open").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
