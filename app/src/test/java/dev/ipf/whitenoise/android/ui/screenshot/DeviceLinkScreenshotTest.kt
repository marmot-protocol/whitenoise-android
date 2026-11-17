package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.DeviceGroupMoveSiblingFfi
import dev.ipf.marmotkit.DeviceGroupMoveStateFfi
import dev.ipf.marmotkit.DeviceGroupMoveViewFfi
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.state.DeviceLinkCommand
import dev.ipf.whitenoise.android.state.LinkedDeviceRoster
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkAvailability
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkOtherProfileDialog
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkRequestContent
import dev.ipf.whitenoise.android.ui.devices.LinkThisDeviceContent
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesContent
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesLinking
import dev.ipf.whitenoise.android.ui.devices.fixtureCode
import dev.ipf.whitenoise.android.ui.devices.fixtureDevice
import dev.ipf.whitenoise.android.ui.devices.newDeviceLinkStep
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

/** Linking on both devices: waiting, the emoji comparison, the approval choices and how a link turns out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class DeviceLinkScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val originalZone: TimeZone = TimeZone.getDefault()

    /** Times render in UTC so the baselines do not depend on the machine's zone. */
    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores the machine's zone for later tests in the same JVM. */
    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    /** The new device waiting for a device already signed in. */
    @Test
    fun newDeviceWaitingDark() {
        newDevice("device_link_new_waiting_dark", NewDeviceLinkStateFfi.WaitingForAnswer, Look(dark = true))
    }

    /** The new device showing the code with They match and Cancel. */
    @Test
    fun newDeviceComparingLight() {
        newDevice("device_link_new_comparing_light", NewDeviceLinkStateFfi.Comparing(fixtureCode()))
    }

    /** The code wraps without clipping in RTL at double font scale. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1400dp-mdpi")
    fun newDeviceComparingRtlLargeFont() {
        val comparing = NewDeviceLinkStateFfi.Comparing(fixtureCode())
        newDevice("device_link_new_comparing_rtl_large_font", comparing, Look(rtl = true))
    }

    /** The new device after MDK reports it linked. */
    @Test
    fun newDeviceLinkedLight() {
        newDevice("device_link_new_linked_light", NewDeviceLinkStateFfi.Linked)
    }

    /** A link that ended because the codes differed, on true-black surfaces. */
    @Test
    fun newDeviceEndedAmoled() {
        val ended = NewDeviceLinkStateFfi.Ended(DeviceLinkEndReasonFfi.CODES_DIFFER)
        newDevice("device_link_new_ended_amoled", ended, Look(dark = true, amoled = true))
    }

    /** The approving device comparing, with the name field and both choices on. */
    @Test
    fun requestComparingLight() {
        request("device_link_request_comparing_light", DeviceLinkPromptStateFfi.Comparing(fixtureCode()))
    }

    /** The approval choices wrap in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h2200dp-mdpi")
    fun requestComparingDarkRtlLargeFont() {
        val comparing = DeviceLinkPromptStateFfi.Comparing(fixtureCode())
        request("device_link_request_comparing_dark_rtl_large_font", comparing, Look(dark = true, rtl = true))
    }

    /** The approving device while the new device joins, with the action showing progress. */
    @Test
    fun requestLinkingLight() {
        request("device_link_request_linking_light", DeviceLinkPromptStateFfi.Linking)
    }

    /** A request that cannot be linked because the account is at its device limit. */
    @Test
    fun requestTooManyDark() {
        request("device_link_request_too_many_dark", DeviceLinkPromptStateFfi.TooManyDevices, Look(dark = true))
    }

    /** A request another device answered. */
    @Test
    fun requestAddedElsewhereLight() {
        val added = DeviceLinkPromptStateFfi.AddedElsewhere("Tablet", "Pixel")
        request("device_link_request_added_elsewhere_light", added)
    }

    /** A request waiting on a profile that is not active. */
    @Test
    fun otherProfileLight() {
        show(Look()) { DeviceLinkOtherProfileDialog("Work profile", onReview = {}, onNotNow = {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/device_link_other_profile_light.png")
    }

    /** The roster of an account with no sibling, offering Link this device and the steps for adding one. */
    @Test
    fun rosterUnlinkedLight() {
        show(Look()) { LinkedDevicesContent(onlyThisDevice(), {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_unlinked_light.png")
    }

    /** The roster of an account that signs through an external signer app. */
    @Test
    fun rosterExternalSignerDark() {
        val linking = LinkedDevicesLinking(availability = DeviceLinkAvailability.ExternalSigner)
        show(Look(dark = true)) { LinkedDevicesContent(onlyThisDevice(), {}, linking) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_external_signer_dark.png")
    }

    /** A finished move with a reminder about a device left behind and a note about rejections it overrode. */
    @Test
    fun rosterMoveMadeLight() {
        val sibling = DeviceGroupMoveSiblingFfi("left", "Laptop", questionSuppressed = true)
        val made = DeviceGroupMoveViewFfi("move", DeviceGroupMoveStateFfi.MADE, listOf(sibling), listOf("slot"))
        show(Look()) { LinkedDevicesContent(onlyThisDevice(), {}, LinkedDevicesLinking(moves = listOf(made))) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_move_made_light.png")
    }

    /** A failed move in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1600dp-mdpi")
    fun rosterMoveFailedDarkRtlLargeFont() {
        val failed = DeviceGroupMoveViewFfi("move", DeviceGroupMoveStateFfi.FAILED, emptyList(), emptyList())
        val linking = LinkedDevicesLinking(moves = listOf(failed))
        show(Look(dark = true, rtl = true)) { LinkedDevicesContent(onlyThisDevice(), {}, linking) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_move_failed_dark_rtl_large_font.png")
    }

    /** A roster listing this device alone. */
    private fun onlyThisDevice(): LinkedDeviceRosterState {
        val self = fixtureDevice("self", label = "Pixel", isThisDevice = true)
        return LinkedDeviceRosterState(roster = LinkedDeviceRoster(self, emptyList(), emptyList()))
    }

    /** Theme, direction and font scale for one capture. */
    private data class Look(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
    )

    /** Records the new device's step for [state] as [name]. */
    private fun newDevice(
        name: String,
        state: NewDeviceLinkStateFfi,
        look: Look = Look(),
    ) {
        show(look) { LinkThisDeviceContent(newDeviceLinkStep(state), running = null, onBack = {}, onAction = {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Records the approving device's request for [state] as [name]. */
    private fun request(
        name: String,
        state: DeviceLinkPromptStateFfi,
        look: Look = Look(),
        running: DeviceLinkCommand? = null,
    ) {
        show(look) { DeviceLinkRequestContent(state, running, onLink = {}, onDecline = {}, onClose = {}) }
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
