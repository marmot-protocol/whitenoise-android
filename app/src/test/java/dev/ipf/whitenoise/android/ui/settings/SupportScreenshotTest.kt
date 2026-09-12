package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Identity and relay recovery frames use no native, networking or message fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SupportScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Configured profile in light mode. */
    @Test fun configuredLight() = render("support_configured_light")

    /** Dark identity and action surface. */
    @Test fun configuredDark() = render("support_configured_dark", dark = true)

    /** True black outlines around identity and action. */
    @Test fun configuredAmoled() = render("support_configured_amoled", dark = true, amoled = true)

    /** Missing receiving relays expose the real recovery destination. */
    @Test fun missingRelaysLight() = render("support_missing_relays_light", state = SupportRelayState.Missing)

    /** Read failure exposes Retry while preserving access to Relays. */
    @Test fun unavailableLight() = render("support_unavailable_light", state = SupportRelayState.Unavailable)

    /** Pending native read has truthful loading text and a disabled action. */
    @Test fun loadingLight() = render("support_loading_light", state = SupportRelayState.Loading)

    /** Existing local DM remains available while relay configuration is unavailable. */
    @Test fun existingChatLight() =
        render(
            "support_existing_chat_light",
            state = SupportRelayState.Unavailable,
            existing = true,
        )

    /** Missing-relay copy wraps and the list remains scrollable at 200% in RTL. */
    @Test fun missingRelaysRtlLargeText() =
        render(
            "support_missing_relays_rtl_200",
            state = SupportRelayState.Missing,
            rtl = true,
            scale = 2f,
        )

    /** High-density AMOLED composition. */
    @Config(qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
    @Test
    fun missingRelaysAmoledXxhdpi() =
        render(
            "support_missing_relays_amoled_xxhdpi",
            state = SupportRelayState.Missing,
            dark = true,
            amoled = true,
        )

    private fun render(
        name: String,
        state: SupportRelayState = SupportRelayState.Configured,
        existing: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    SupportContent(true, existing, state, false, {}, {}, {}, {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
