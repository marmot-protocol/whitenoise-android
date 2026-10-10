package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
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

    @Test
    fun listLight() = capture("list_light", dark = false)

    @Test
    fun listDark() = capture("list_dark", dark = true)

    @Test
    fun selectedAmoled() = capture("text_amoled", dark = true, selected = true)

    @Test
    fun failureLargeRtl() = capture("failure_large_rtl", dark = true, failed = true, largeRtl = true)

    private fun capture(
        suffix: String,
        dark: Boolean,
        selected: Boolean = false,
        failed: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val notice = OpenSourceNotice(
            "example",
            "Example library",
            "Copyright Example\n\nFull licence notice text.",
        )
        val result: Result<List<OpenSourceNotice>> = if (failed) Result.failure(IllegalArgumentException()) else Result.success(listOf(notice))
        composeRule.setContent {
            val density = LocalDensity.current
            val title = LocalContext.current.getString(R.string.open_source_licenses)
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = selected) {
                    SettingsScaffold(title = title, onBack = {}) {
                        OpenSourceLicensesContent(result, if (selected) notice else null, {}, {})
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/licenses_$suffix.png")
    }
}
