package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.common.AppLockScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the opaque lock surface across appearance, native error and compact accessibility states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AppLockScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Default lock retains the prototype's spacing, lock glyph and neutral Unlock action. */
    @Test
    fun lockedLight() = capture("app_lock_light")

    /** Dark mode uses its own surface and on-surface icon. */
    @Test
    fun lockedDark() = capture("app_lock_dark", dark = true)

    /** AMOLED gives the neutral Unlock button its required visible outline. */
    @Test
    fun lockedAmoled() = capture("app_lock_amoled", dark = true, amoled = true)

    /** Device-authentication cancellation is explicit while the cover remains opaque. */
    @Test
    fun cancelledDark() =
        capture(
            "app_lock_cancelled_dark",
            dark = true,
            error = AppText.Resource(R.string.app_lock_auth_cancelled),
        )

    /** Real pending timestamp evaluation shows checking rather than an actionable unlock. */
    @Test
    fun evaluatingLight() = capture("app_lock_evaluating_light", evaluating = true)

    /** Two-times text in RTL preserves the centered layout and wrapping native error text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h780dp-mdpi")
    fun errorRtlLargeText() =
        capture(
            "app_lock_error_rtl_large",
            largeRtl = true,
            error = AppText.Resource(R.string.app_lock_auth_failed),
        )

    /** A short window exposes a scrollable cover, rather than overflowing under the system bars. */
    @Test
    @Config(qualifiers = "en-w640dp-h280dp-mdpi")
    fun cancelledLandscape() =
        capture(
            "app_lock_cancelled_landscape",
            error = AppText.Resource(R.string.app_lock_auth_cancelled),
        )

    /** Static caller fixtures exercise presentation only; the progress animation uses a fixed frame. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        error: AppText? = null,
        evaluating: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        if (evaluating) composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    AppLockScreen(error = error, onRetry = {}, evaluating = evaluating)
                }
            }
        }
        if (evaluating) composeRule.mainClock.advanceTimeBy(160)
        composeRule.onNodeWithTag("app.lock").captureRoboImage("src/test/snapshots/$name.png")
    }
}
