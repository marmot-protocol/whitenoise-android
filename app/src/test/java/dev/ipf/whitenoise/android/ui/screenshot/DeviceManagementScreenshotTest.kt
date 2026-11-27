package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import dev.ipf.whitenoise.android.state.DeviceLinkAttempt
import dev.ipf.whitenoise.android.state.LinkedDeviceChatCounts
import dev.ipf.whitenoise.android.state.SignInListState
import dev.ipf.whitenoise.android.ui.devices.DEVICE_DETAIL_REMOVE_TAG
import dev.ipf.whitenoise.android.ui.devices.DEVICE_DETAIL_RENAME_TAG
import dev.ipf.whitenoise.android.ui.devices.LINKED_DEVICES_REPAIR_TAG
import dev.ipf.whitenoise.android.ui.devices.LinkedDeviceDetailContent
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesContent
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesHealth
import dev.ipf.whitenoise.android.ui.devices.RepairDeviceLinkDialog
import dev.ipf.whitenoise.android.ui.devices.SignInReviewDialog
import dev.ipf.whitenoise.android.ui.devices.SignInReviewState
import dev.ipf.whitenoise.android.ui.devices.fixtureDevice
import dev.ipf.whitenoise.android.ui.devices.fixtureRosterState
import dev.ipf.whitenoise.android.ui.devices.signIn
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

/** A device's page, the roster's standing notices and counts, and the removal, repair and sign-in review dialogs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class DeviceManagementScreenshotTest {
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

    /** Another device's page with its settings and removal. */
    @Test
    fun otherDeviceLight() {
        val tablet = fixtureDevice("tablet", label = "Tablet", lastActiveAtSeconds = 1_794_488_400)
        show(Look()) { LinkedDeviceDetailContent(tablet, busy = false, onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_device_detail_other_light.png")
    }

    /** This device's page, which has no removal. */
    @Test
    fun thisDeviceDark() {
        val self = fixtureDevice("self", label = "Pixel", isThisDevice = true)
        show(Look(dark = true)) { LinkedDeviceDetailContent(self, busy = false, onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_device_detail_this_dark.png")
    }

    /** A device being removed, in RTL at double font scale on true-black surfaces. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h2200dp-mdpi")
    fun removingDeviceAmoledRtlLargeFont() {
        val old = fixtureDevice("old", label = "Old phone", removalPending = true)
        show(Look(dark = true, amoled = true, rtl = true)) { LinkedDeviceDetailContent(old, busy = false, onBack = {}) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_device_detail_removing_amoled_rtl_large_font.png")
    }

    /** The rename dialog over a device's page. */
    @Test
    fun renameDialogLight() {
        val tablet = fixtureDevice("tablet", label = "Tablet")
        show(Look()) { LinkedDeviceDetailContent(tablet, busy = false, onBack = {}) }
        compose.onNodeWithTag(DEVICE_DETAIL_RENAME_TAG).performClick()
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/linked_device_rename_light.png")
    }

    /** The removal confirmation. */
    @Test
    fun removeDialogDark() {
        val tablet = fixtureDevice("tablet", label = "Tablet")
        show(Look(dark = true)) { LinkedDeviceDetailContent(tablet, busy = false, onBack = {}) }
        compose.onNodeWithTag(DEVICE_DETAIL_REMOVE_TAG).performScrollTo().performClick()
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/linked_device_remove_dark.png")
    }

    @Test
    fun expiredLinkAttemptLight() {
        val attempt = DeviceLinkAttempt("expired", 1_794_488_400L, reviewed = false)
        val health = LinkedDevicesHealth(missedLinks = listOf(attempt))
        show(Look()) { LinkedDevicesContent(fixtureRosterState(), onBack = {}, health = health) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_expired_attempt_light.png")
    }

    /** The roster with a security warning, a stalled device, an overdue refresh, chat counts and sign-ins. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h2600dp-mdpi")
    fun rosterWithEverythingLight() {
        show(Look()) { LinkedDevicesContent(fixtureRosterState(), onBack = {}, health = everything()) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_health_light.png")
    }

    /** The same roster in dark theme, RTL and large text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h6000dp-mdpi")
    fun rosterWithEverythingDarkRtlLargeFont() {
        val health = everything()
        show(Look(dark = true, rtl = true)) { LinkedDevicesContent(fixtureRosterState(), onBack = {}, health = health) }
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_health_dark_rtl_large_font.png")
    }

    /** Reviewing a new sign-in: Not now, Mine, don't link and This wasn't me. */
    @Test
    fun signInReviewNewLight() {
        show(Look()) { SignInReviewDialog(signIn("a"), onAnswer = {}, onClose = {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/sign_in_review_new_light.png")
    }

    /** Reviewing a sign-in from before this device, with the missing-link note and I don't recognize this. */
    @Test
    fun signInReviewBeforeDark() {
        val before = signIn("a", beforeThisDevice = true).copy(previouslyLinkedHere = true)
        show(Look(dark = true)) { SignInReviewDialog(before, onAnswer = {}, onClose = {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/sign_in_review_before_dark.png")
    }

    /** A sign-in another device rejected, which can only be dismissed here. */
    @Test
    fun signInReviewRejectedElsewhereLight() {
        val rejected = signIn("a").copy(state = UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE)
        show(Look()) { SignInReviewDialog(rejected, onAnswer = {}, onClose = {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/sign_in_review_rejected_elsewhere_light.png")
    }

    /** An uncertain discard keeps the repair explanation and its action together on an otherwise healthy roster. */
    @Test
    fun repairNeedsConfirmation() {
        val health = LinkedDevicesHealth(repairNeedsConfirmation = true)
        show(Look()) { LinkedDevicesContent(fixtureRosterState(), onBack = {}, health = health) }
        compose.onNodeWithTag(LINKED_DEVICES_REPAIR_TAG).performScrollTo()
        compose.onRoot().captureRoboImage("src/test/snapshots/linked_devices_repair_needs_confirmation.png")
    }

    /** The repair explanation. */
    @Test
    fun repairDialogLight() {
        show(Look()) { RepairDeviceLinkDialog(onRepair = {}, onDismiss = {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/linked_devices_repair_light.png")
    }

    /**
     * Every standing notice at once: warning, stalled, overdue, both counts, a failed check and three sign-ins, one of
     * them marked Mine, don't link.
     */
    private fun everything(): LinkedDevicesHealth {
        val warning = CompromiseWarningItemFfi(CompromiseWarningReasonFfi.REJECTED_SIGN_IN, "slot", null, 1u)
        val notNow = signIn("b", beforeThisDevice = true).copy(state = UnrecognizedSignInStateFfi.NOT_NOW)
        val mine = signIn("c").copy(client = "Desktop bot", state = UnrecognizedSignInStateFfi.NOT_NOW)
        val list = SignInListState(listOf(signIn("a"), notNow, mine), loaded = true, checkFailed = true)
        return LinkedDevicesHealth(
            counts = LinkedDeviceChatCounts(closedToLinkedDevices = 3, holdingRemovedDevice = 1),
            keyPackageRefreshOverdue = true,
            stalled = true,
            review = SignInReviewState(list = list, warning = listOf(warning), startedFresh = true, mine = setOf("c")),
        )
    }

    /** Theme, direction and font scale for one capture. */
    private data class Look(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
    )

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
