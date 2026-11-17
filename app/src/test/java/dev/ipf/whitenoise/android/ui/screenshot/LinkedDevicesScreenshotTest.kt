package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.LinkedDeviceRoster
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesContent
import dev.ipf.whitenoise.android.ui.devices.fixtureRosterState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** The linked-device roster in each read state, across themes, RTL and large text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class LinkedDevicesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val originalZone: TimeZone = TimeZone.getDefault()

    /** Dates render in UTC so the baselines do not depend on the machine's zone. */
    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores the machine's zone for later tests in the same JVM. */
    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    /** This device, two siblings and a pending removal in the light theme. */
    @Test fun rosterLight() = capture("linked_devices_roster_light", fixtureRosterState())

    /** The same roster in the dark theme. */
    @Test fun rosterDark() = capture("linked_devices_roster_dark", fixtureRosterState(), dark = true)

    /** The same roster on true-black surfaces. */
    @Test fun rosterAmoled() = capture("linked_devices_roster_amoled", fixtureRosterState(), dark = true, amoled = true)

    /** The roster wraps without clipping in RTL at double font scale. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1600dp-mdpi")
    fun rosterRtlLargeFont() = capture("linked_devices_roster_rtl_large_font", fixtureRosterState(), rtl = true)

    /** A failed refresh keeps the rows under the failure notice and Retry. */
    @Test
    fun refreshFailedKeepsRows() {
        capture("linked_devices_refresh_failed_light", fixtureRosterState().copy(readFailed = true))
    }

    /** A failed first read shows the failure alone. */
    @Test
    fun firstReadFailed() {
        capture("linked_devices_first_read_failed_dark", LinkedDeviceRosterState(readFailed = true), dark = true)
    }

    /** The first read in progress. */
    @Test fun loading() = capture("linked_devices_loading_light", LinkedDeviceRosterState(refreshing = true))

    /** An account with nothing linked. */
    @Test
    fun empty() =
        capture(
            "linked_devices_empty_light",
            LinkedDeviceRosterState(roster = LinkedDeviceRoster(null, emptyList(), emptyList())),
        )

    /** Renders [state] in the requested theme, direction and font scale and records [name]. */
    private fun capture(
        name: String,
        state: LinkedDeviceRosterState,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    LinkedDevicesContent(state, onBack = {}, onRetry = {})
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
