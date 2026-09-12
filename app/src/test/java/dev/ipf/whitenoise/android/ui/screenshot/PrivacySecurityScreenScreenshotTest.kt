package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.ui.settings.DevicePrivacyScreen
import dev.ipf.whitenoise.android.ui.settings.privacyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Privacy & Security with a device credential, the lock on (Auto-lock visible) and usage sharing granted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class PrivacySecurityScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun privacySecurityLight() = capture("privacy_security_light", darkTheme = false)

    /** Dark theme. */
    @Test
    fun privacySecurityDark() = capture("privacy_security_dark", darkTheme = true)

    /** AMOLED: outlined groups on black. */
    @Test
    fun privacySecurityAmoled() = capture("privacy_security_amoled", darkTheme = true, amoled = true)

    /** RTL at 200 %: wrapping subtitles and mirrored switches. */
    @Test
    fun privacySecurityRtlLargeFont() {
        capture(
            "privacy_security_rtl_large_font",
            darkTheme = false,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    /** Renders the screen against the fixed native privacy fake and records the window. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val state =
            privacyAppState(
                decision = UsageDiagnosticsDecisionFfi.GRANTED,
                preferencesName = "privacy-screenshot-$name",
                seedPreferences = { putBoolean("require_app_unlock", true) },
            )
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    DevicePrivacyScreen(
                        appState = state,
                        onBack = {},
                        onOpenDiagnostics = {},
                        credentialAvailableOverride = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
