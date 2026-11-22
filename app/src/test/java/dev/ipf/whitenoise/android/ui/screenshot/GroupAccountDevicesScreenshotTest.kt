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
import dev.ipf.marmotkit.AccountDevicesActivationFfi
import dev.ipf.whitenoise.android.ui.group.GroupAccountDevicesConfirmDialog
import dev.ipf.whitenoise.android.ui.group.groupAccountDevicesRows
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A group's linked-devices row in each answer MDK gives, and the confirmation before turning it on. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h200dp-mdpi")
class GroupAccountDevicesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** An eligible group offers the action. */
    @Test
    fun eligibleLight() {
        rows("group_account_devices_eligible_light", AccountDevicesActivationFfi.ELIGIBLE)
    }

    /** A group that already has it says so. */
    @Test
    fun enabledDark() {
        rows("group_account_devices_enabled_dark", AccountDevicesActivationFfi.ALREADY_ENABLED, dark = true)
    }

    /** An ineligible group's reason wraps in RTL at double font scale on true-black surfaces. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h500dp-mdpi")
    fun membersNeedUpdateAmoledRtlLargeFont() {
        val name = "group_account_devices_members_need_update_amoled_rtl_large_font"
        rows(name, AccountDevicesActivationFfi.MEMBERS_NEED_UPDATE, dark = true, amoled = true, rtl = true)
    }

    /** The confirmation that says the change cannot be undone. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h700dp-mdpi")
    fun confirmLight() {
        show(dark = false, amoled = false, rtl = false) { GroupAccountDevicesConfirmDialog({}, {}) }
        compose.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_account_devices_confirm_light.png")
    }

    /** Records the row for [activation] as [name]. */
    private fun rows(
        name: String,
        activation: AccountDevicesActivationFfi,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        show(dark, amoled, rtl) { SettingsGroup { groupAccountDevicesRows(activation, enabled = true) {} } }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Renders [content] in the requested theme, direction and font scale. */
    private fun show(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, content = content)
            }
        }
    }
}
