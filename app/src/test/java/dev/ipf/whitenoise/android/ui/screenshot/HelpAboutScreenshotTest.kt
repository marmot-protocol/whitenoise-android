package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.AboutContent
import dev.ipf.whitenoise.android.ui.settings.BugReportContent
import dev.ipf.whitenoise.android.ui.settings.HelpScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Help, the reviewed bug report with its pinned action, About's build facts, and a refused hand-off. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class HelpAboutScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Help in the light theme. */
    @Test
    fun helpLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                HelpScreen(onBack = {}, onOpenBugReport = {}, onOpenAbout = {})
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/help_light.png")
    }

    /** Help on black, where the group is outlined. */
    @Test
    fun helpAmoled() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                HelpScreen(onBack = {}, onOpenBugReport = {}, onOpenAbout = {})
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/help_amoled.png")
    }

    /** The reviewed bug report: destination, both privacy callouts and the pinned action. */
    @Test
    fun bugReportLight() {
        renderBugReport(dark = false, accepted = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/help_bug_report_light.png")
    }

    /** The same screen in the dark theme. */
    @Test
    fun bugReportDark() {
        renderBugReport(dark = true, accepted = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/help_bug_report_dark.png")
    }

    /** A refused hand-off: the recoverable dialog over the screen. */
    @Test
    fun bugReportRefusedLight() {
        renderBugReport(dark = false, accepted = false)
        composeRule.onNodeWithTag("help.bug.open").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("help.open_failed").captureRoboImage("src/test/snapshots/help_open_failed_light.png")
    }

    /** About in the light theme. */
    @Test
    fun aboutLight() {
        renderAbout(dark = false, amoled = false)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/about_licenses_light.png")
    }

    /** About in the dark theme. */
    @Test
    fun aboutDark() {
        renderAbout(dark = true, amoled = false)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/about_licenses_dark.png")
    }

    /** About on black. */
    @Test
    fun aboutAmoled() {
        renderAbout(dark = true, amoled = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/about_licenses_amoled.png")
    }

    private fun renderBugReport(
        dark: Boolean,
        accepted: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                BugReportContent(onBack = {}, onOpenReport = { accepted })
            }
        }
    }

    private fun renderAbout(
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                AboutContent(
                    versionName = "1.4.0",
                    buildNumber = "140",
                    mdkShortSha = "0a5ab20",
                    onBack = {},
                    onOpenLicenses = { true },
                    onOpenPrivacy = { true },
                )
            }
        }
    }
}
