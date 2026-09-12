package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Startup loading/failure geometry with deterministic native-safe display records. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartupRecoveryScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Prototype loading mark and caption. */
    @Test fun loadingLight() = capture("loading_light", loading = true)

    /** Recovery retains the real message and retry/copy actions. */
    @Test fun failureLight() = capture("failure_light")

    /** Dark recovery palette. */
    @Test fun failureDark() = capture("failure_dark", dark = true)

    /** Black recovery surface and shared primary outline. */
    @Test fun failureAmoled() = capture("failure_amoled", dark = true, amoled = true)

    /** Wide startup pane retains the 520 dp content bound. */
    @Test
    @Config(qualifiers = "en-w1000dp-h780dp-mdpi")
    fun tablet() = capture("tablet")

    /** Large RTL text can grow within the scrollable pane. */
    @Test fun rtlLargeText() = capture("rtl_200", rtl = true, scale = 2f)

    /** Terminal failures retain diagnostics without a misleading retry action. */
    @Test fun terminal() = capture("terminal", retryable = false)

    /** A short landscape frame leaves recovery controls reachable by scrolling. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun shortWindow() = capture("short_200", scale = 2f)

    /** Captures visuals only; no bootstrap or profile selection is simulated by this renderer. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        loading: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        retryable: Boolean = true,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    if (loading) {
                        StartupLoadingScreen()
                    } else {
                        StartupFailureScreen(
                            title = "White Noise couldn't start",
                            error =
                                ErrorPresentation(
                                    AppText.Plain("Startup is taking longer than expected. Please try again."),
                                    "operation=APP_BOOTSTRAP_TIMEOUT",
                                    retryable,
                                ),
                            onRetry = {},
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/startup_recovery_$name.png")
    }
}
