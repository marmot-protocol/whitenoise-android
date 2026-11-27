package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkRequestBanner
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkRequestContent
import dev.ipf.whitenoise.android.ui.devices.fixtureCode
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat list's link request notice, its repeated-request warning, and the warning step it opens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class DeviceLinkRequestNoticeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** A device asking to link to the active profile. */
    @Test
    fun noticeLight() {
        notice("device_link_request_notice_light", repeated = false, profileName = null)
    }

    /** A device asking to link to another profile, in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h900dp-mdpi")
    fun noticeOtherProfileDarkRtlLargeFont() {
        notice(
            "device_link_request_notice_other_profile_dark_rtl_large_font",
            false,
            "Work profile",
            Look(dark = true, rtl = true),
        )
    }

    /** Repeated requests on the active profile turn the notice into a warning. */
    @Test
    fun repeatedNoticeLight() {
        notice("device_link_request_repeated_notice_light", repeated = true, profileName = null)
    }

    /** The warning for another profile on true-black surfaces. */
    @Test
    fun repeatedNoticeOtherProfileAmoled() {
        notice(
            "device_link_request_repeated_notice_other_profile_amoled",
            true,
            "Work profile",
            Look(dark = true, amoled = true),
        )
    }

    /** The warning in RTL at double font scale wraps its text and actions without clipping. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h900dp-mdpi")
    fun repeatedNoticeRtlLargeFont() {
        notice("device_link_request_repeated_notice_rtl_large_font", true, null, Look(rtl = true))
    }

    /** A repeated request opened from the notice: no code and no Link, only Don't link and Review devices. */
    @Test
    fun repeatedRequestLight() {
        repeatedRequest("device_link_request_repeated_light")
    }

    /** The warning step in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1400dp-mdpi")
    fun repeatedRequestDarkRtlLargeFont() {
        repeatedRequest("device_link_request_repeated_dark_rtl_large_font", Look(dark = true, rtl = true))
    }

    /** Theme, direction and font scale for one capture. */
    private data class Look(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
    )

    /** Records the notice as [name]. */
    private fun notice(
        name: String,
        repeated: Boolean,
        profileName: String?,
        look: Look = Look(),
    ) {
        show(look) { DeviceLinkRequestBanner(repeated, profileName, onReview = {}, onNotNow = {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Records a repeated request's warning step as [name]. */
    private fun repeatedRequest(
        name: String,
        look: Look = Look(),
    ) {
        val comparing = DeviceLinkPromptStateFfi.Comparing(fixtureCode())
        show(look) { DeviceLinkRequestContent(comparing, null, {}, {}, {}, repeated = true) }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Renders [content] with [look]. */
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
                WhiteNoiseTheme(darkTheme = look.dark, amoled = look.amoled, content = content)
            }
        }
    }
}
