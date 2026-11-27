package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.whitenoise.android.state.SignInAlertTier
import dev.ipf.whitenoise.android.ui.devices.CompromiseAlertContent
import dev.ipf.whitenoise.android.ui.devices.SignInAlertBanner
import dev.ipf.whitenoise.android.ui.profile.ProfileReadiness
import dev.ipf.whitenoise.android.ui.settings.AccountHubGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat list's sign-in banner and red card, the full-screen compromise alert, and the Devices row badge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class SignInAlertScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** The neutral banner for a new sign-in to review. */
    @Test
    fun reviewBannerLight() {
        show(Look()) { Banner(SignInAlertTier.Review) }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_review_banner_light.png")
    }

    /** The neutral banner in dark theme. */
    @Test
    fun reviewBannerDark() {
        show(Look(dark = true)) { Banner(SignInAlertTier.Review) }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_review_banner_dark.png")
    }

    /** The red card while the compromise warning is open, with both reasons. */
    @Test
    fun compromiseCardLight() {
        show(Look()) { Banner(SignInAlertTier.Compromise) }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_compromise_card_light.png")
    }

    /** The red card in RTL at double font scale on true-black surfaces. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1400dp-mdpi")
    fun compromiseCardAmoledRtlLargeFont() {
        show(Look(dark = true, amoled = true, rtl = true)) { Banner(SignInAlertTier.Compromise) }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_compromise_card_amoled_rtl_large_font.png")
    }

    /** The full-screen alert with its reasons, steps and three actions. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h900dp-mdpi")
    fun compromiseAlertLight() {
        show(Look()) { Alert() }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_compromise_alert_light.png")
    }

    /** The full-screen alert in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h2000dp-mdpi")
    fun compromiseAlertDarkRtlLargeFont() {
        show(Look(dark = true, rtl = true)) { Alert() }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_compromise_alert_dark_rtl_large_font.png")
    }

    /** The Account hub's Devices row with the review dot, then with the warning dot. */
    @Test
    fun devicesRowBadgesLight() {
        show(Look()) { Rows() }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_devices_row_badges_light.png")
    }

    /** The same rows in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1400dp-mdpi")
    fun devicesRowBadgesDarkRtlLargeFont() {
        show(Look(dark = true, rtl = true)) { Rows() }
        compose.onRoot().captureRoboImage("src/test/snapshots/sign_in_alert_devices_row_badges_dark_rtl_large_font.png")
    }

    @Composable
    private fun Banner(tier: SignInAlertTier) {
        SignInAlertBanner(tier = tier, warning = WARNING, hiding = false, onReview = {}, onHide = {})
    }

    @Composable
    private fun Alert() {
        CompromiseAlertContent(warning = WARNING, hiding = false, onReview = {}, onHide = {}, onNotNow = {})
    }

    @Composable
    private fun Rows() {
        Column {
            listOf(SignInAlertTier.Review, SignInAlertTier.Compromise).forEach { tier ->
                AccountHubGroup(
                    ProfileReadiness.Loading,
                    onOpenLinkedDevices = {},
                    onOpenDetail = {},
                    devicesTier = tier,
                )
            }
        }
    }

    /** Theme, direction and font scale for one capture. */
    private data class Look(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
    )

    /** Renders [content] on the theme's background with [look]. */
    private fun show(
        look: Look,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (look.rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (look.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = look.dark, amoled = look.amoled) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
    }

    private companion object {
        val WARNING =
            listOf(
                CompromiseWarningItemFfi(CompromiseWarningReasonFfi.REJECTED_SIGN_IN, "a", null, 1u),
                CompromiseWarningItemFfi(CompromiseWarningReasonFfi.FOREIGN_OWN_SLOT_KEY_PACKAGE, "b", "ref", 2u),
            )
    }
}
