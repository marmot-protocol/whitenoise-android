package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.DeveloperBuildFacts
import dev.ipf.whitenoise.android.ui.settings.DeveloperContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Developer Tools with the switch off, with it on, and on black. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class DeveloperScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Developer mode off: the warning, the switch, Key Packages and the build. */
    @Test
    fun developerToolsOffLight() = capture("developer_tools_light", developerMode = false, dark = false, amoled = false)

    /** Developer mode on: the debugging group appears between them. */
    @Test
    fun developerToolsOnLight() =
        capture(
            "developer_tools_enabled_light",
            developerMode = true,
            dark = false,
            amoled = false,
        )

    /** The same, outlined on black. */
    @Test
    fun developerToolsOnAmoled() =
        capture(
            "developer_tools_enabled_amoled",
            developerMode = true,
            dark = true,
            amoled = true,
        )

    /** AMOLED connected row seams also hold at three physical pixels per dp. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
    fun developerToolsOnAmoledXxhdpi() =
        capture(
            "developer_tools_enabled_amoled_xxhdpi",
            developerMode = true,
            dark = true,
            amoled = true,
        )

    /** Standard dark retains tonal depth independently of AMOLED. */
    @Test
    fun developerToolsOnDark() =
        capture(
            "developer_tools_enabled_dark",
            developerMode = true,
            dark = true,
            amoled = false,
        )

    /** Two-times text in RTL keeps the warning, mode control and recovery navigation scrollable. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun developerToolsRtlLargeText() =
        capture(
            "developer_tools_rtl_large",
            developerMode = true,
            dark = false,
            amoled = false,
            largeRtl = true,
        )

    /** Staging identifies the installed variant at the end of the build facts. */
    @Test
    fun developerToolsStagingBuild() =
        capture(
            "developer_tools_staging",
            developerMode = false,
            dark = false,
            amoled = false,
            staging = true,
        )

    /** Captures one compact viewport, scrolling only when the case specifically targets the build badge. */
    private fun capture(
        name: String,
        developerMode: Boolean,
        dark: Boolean,
        amoled: Boolean,
        largeRtl: Boolean = false,
        staging: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    DeveloperContent(
                        developerMode = developerMode,
                        streamingDebug = developerMode,
                        build = DeveloperBuildFacts("1.4.0", "140", "0a5ab20", staging),
                        onDeveloperModeChange = {},
                        onStreamingDebugChange = {},
                        onBack = {},
                        onOpenDiagnostics = {},
                        onOpenKeyPackages = {},
                    )
                }
            }
        }
        if (staging) {
            composeRule.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("developer.staging"))
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
