package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.OpenSourceLicensesContent
import dev.ipf.whitenoise.android.ui.settings.OpenSourceNotice
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class OpenSourceLicensesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Preserve the light-theme list hierarchy. */
    @Test
    fun listLight() = capture("list_light", dark = false)

    /** Preserve contrast for the dark-theme list. */
    @Test
    fun listDark() = capture("list_dark", dark = true)

    /** Keep the touching AMOLED row seams and section headers distinct. */
    @Test
    fun listAmoled() = capture("list_amoled", dark = true, amoled = true)

    /** Keep section headers and wrapped rows readable with large RTL text. */
    @Test
    fun listLargeRtl() = capture("list_large_rtl", dark = false, largeRtl = true)

    /** Preserve the full-text reader on the AMOLED surface. */
    @Test
    fun selectedAmoled() = capture("text_amoled", dark = true, amoled = true, selected = true)

    /** Keep the selected notice's full text readable with large RTL text. */
    @Test
    fun selectedLargeRtl() = capture("text_large_rtl", dark = false, selected = true, largeRtl = true)

    /** Keep retry legible with large RTL text. */
    @Test
    fun failureLargeRtl() = capture("failure_large_rtl", dark = true, failed = true, largeRtl = true)

    /** Verify the intended reader state before comparing its existing rendering baseline. */
    private fun capture(
        suffix: String,
        dark: Boolean,
        amoled: Boolean = false,
        selected: Boolean = false,
        failed: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val notice =
            OpenSourceNotice(
                "example",
                "Example library",
                "Copyright Example\n\nFull licence notice text.",
            )
        val apache = "http://www.apache.org/licenses/LICENSE-2.0.txt"
        val notices =
            listOf(
                OpenSourceNotice("activity", "Activity", apache),
                OpenSourceNotice("arch", "Android Arch-Common", apache),
                OpenSourceNotice("camera", "Camera Core", apache),
                OpenSourceNotice("errorprone", "error-prone annotations", apache),
                notice,
                OpenSourceNotice("flatbuffers", "FlatBuffers", apache),
            )
        val result: Result<List<OpenSourceNotice>> =
            if (failed) {
                Result.failure(IllegalArgumentException())
            } else {
                Result.success(notices)
            }
        composeRule.setContent {
            val density = LocalDensity.current
            val title = stringResource(R.string.open_source_licenses)
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    SettingsScaffold(title = title, onBack = {}) {
                        OpenSourceLicensesContent(result, if (selected) notice else null, {}, {})
                    }
                }
            }
        }
        when {
            failed -> composeRule.onNodeWithTag("licenses.failed").assertIsDisplayed()
            selected -> composeRule.onNodeWithTag("licenses.text").assertTextEquals(notice.text)
            else -> composeRule.onNodeWithTag("licenses.list").assertIsDisplayed()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/licenses_$suffix.png")
    }
}
