package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.GroupRelaySettingsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Group relays: configured rows with a wrapped long URL, the configuration-only note and the route-control callout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h900dp-mdpi")
class GroupRelaySettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Member view in the light theme. */
    @Test
    fun memberLight() = capture("group_relays_member_light.png", dark = false)

    /** Admin view in the dark theme, explaining editing is not yet available. */
    @Test
    fun adminDark() = capture("group_relays_admin_dark.png", dark = true, isAdmin = true)

    /** Member view on AMOLED: outlined groups on black. */
    @Test
    fun memberAmoled() = capture("group_relays_member_amoled.png", dark = true, amoled = true)

    /** Empty configured route in the light theme. */
    @Test
    fun emptyLight() = capture("group_relays_empty_light.png", dark = false, relays = emptyList())

    /** Right-to-left at 200% text: long URLs wrap and copy actions stay reachable. */
    @Test
    fun memberRtlLargeFont() = capture("group_relays_member_rtl_large.png", dark = false, largeRtl = true)

    /** Renders one fixed route in the requested theme, direction and font scale and records the window. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        isAdmin: Boolean = false,
        largeRtl: Boolean = false,
        relays: List<String> = RELAYS,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    GroupRelaySettingsScreen(relays = relays, isDm = false, isAdmin = isAdmin, onBack = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name")
    }

    private companion object {
        val RELAYS =
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://a-very-long-relay-hostname-that-keeps-going.example-relay-operator.example.com" +
                    "/nostr/v1/group-route?region=eu-central",
                "wss://relay.eu.whitenoise.chat",
            )
    }
}
