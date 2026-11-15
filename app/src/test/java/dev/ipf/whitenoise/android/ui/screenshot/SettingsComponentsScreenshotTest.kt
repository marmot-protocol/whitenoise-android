package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsChoice
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Positional corners, wrapping values, disabled chrome, and physical-pixel AMOLED seams. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class SettingsComponentsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A three-row group and a singleton cover every Material positional shape. */
    @Test
    fun groupedRowsLight() {
        render()
        capture("light")
    }

    /** The current dark palette supplies the group fill and readable disabled content. */
    @Test
    fun groupedRowsDark() {
        render(darkTheme = true)
        capture("dark")
    }

    /** Long values wrap beneath the title; RTL mirrors the link chevron and start alignment. */
    @Test
    fun groupedRowsRtlLargeFont() {
        render(layoutDirection = LayoutDirection.Rtl, fontScale = 2f)
        capture("rtl_large_font")
    }

    /** At mdpi both the perimeter and seam are one physical pixel, with no doubled join. */
    @Test
    fun groupedRowsAmoledMdpi() {
        render(darkTheme = true, amoled = true)
        capture("amoled_mdpi")
    }

    /** At xxhdpi the perimeter is three pixels while the shared divider remains one pixel. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h900dp-xxhdpi")
    fun groupedRowsAmoledXxhdpi() {
        render(darkTheme = true, amoled = true)
        capture("amoled_xxhdpi")
    }

    /** Disabled and busy rows of every type leave the AMOLED perimeter one uniform outline (#3585). */
    @Test
    fun mixedStatesAmoledMdpi() {
        render(darkTheme = true, amoled = true, mixedStates = true)
        capture("mixed_states_amoled_mdpi")
    }

    /** The uniform AMOLED perimeter holds with RTL mirroring and 200% text in the mixed-state groups. */
    @Test
    fun mixedStatesAmoledRtlLargeFont() {
        render(
            darkTheme = true,
            amoled = true,
            layoutDirection = LayoutDirection.Rtl,
            fontScale = 2f,
            mixedStates = true,
        )
        capture("mixed_states_amoled_rtl_large_font")
    }

    /**
     * Removing both neighbors recomputes a former middle row into a fully rounded singleton.
     * The three-row starting state is already pinned by [groupedRowsAmoledMdpi].
     */
    @Test
    fun removingRowsRecomputesSingleton() {
        val expanded = mutableStateOf(true)
        render(darkTheme = true, amoled = true, expanded = expanded)
        composeRule.runOnIdle { expanded.value = false }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        capture("dynamic_singleton")
    }

    /** Use only real Material row controls and the app theme, with caller-owned visibility. */
    private fun render(
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        expanded: MutableState<Boolean> = mutableStateOf(true),
        mixedStates: Boolean = false,
    ) {
        // Pin the Material loading animation to the same frame for each density and theme.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        Column(
                            // Groups own the 16 dp side margin themselves.
                            modifier = Modifier.padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            if (mixedStates) MixedStateGroups() else SampleGroups(expanded.value)
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    /** Three conditional rows plus a busy singleton exercise first, middle, last, and single positions. */
    @Suppress("FunctionNaming")
    @Composable
    private fun SampleGroups(expanded: Boolean) {
        SettingsGroup {
            if (expanded) {
                row("notifications") { context ->
                    SettingsSwitch(
                        context = context,
                        title = "Notifications",
                        subtitle = "Show message previews",
                        checked = true,
                        onCheckedChange = {},
                        leading = { SettingsLeadingIcon(R.drawable.ic_settings_notifications) },
                    )
                }
            }
            row("language") { context ->
                SettingsLink(
                    context = context,
                    title = "Language",
                    value = "Follow the language selected in your device settings",
                    onClick = {},
                    leading = { SettingsLeadingIcon(R.drawable.ic_language) },
                )
            }
            if (expanded) {
                row("sync") { context ->
                    SettingsSwitch(
                        context = context,
                        title = "Background sync",
                        subtitle = "Saving your preference",
                        checked = false,
                        onCheckedChange = {},
                        busy = true,
                        leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                    )
                }
            }
        }
        SettingsGroup {
            row("account") { context ->
                SettingsLink(
                    context = context,
                    title = "Account details",
                    subtitle = "Unavailable while switching accounts",
                    busy = true,
                    onClick = {},
                    leading = { SettingsLeadingIcon(R.drawable.ic_person) },
                )
            }
        }
    }

    /**
     * A group whose first, middle and last rows mix enabled, disabled and busy states across all four
     * row types, beside an all-disabled group, so any dimmed perimeter segment shows against its neighbors.
     */
    @Suppress("FunctionNaming", "LongMethod")
    @Composable
    private fun MixedStateGroups() {
        SettingsGroup {
            row("disabled_action") { context ->
                SettingsAction(
                    context = context,
                    title = "Transfer administration",
                    subtitle = "Every member is already an admin",
                    enabled = false,
                    onClick = {},
                    leading = { SettingsLeadingIcon(R.drawable.ic_swap_vert) },
                )
            }
            row("enabled_link") { context ->
                SettingsLink(
                    context = context,
                    title = "Members",
                    value = "6",
                    onClick = {},
                    leading = { SettingsLeadingIcon(R.drawable.ic_group) },
                )
            }
            row("busy_switch") { context ->
                SettingsSwitch(
                    context = context,
                    title = "Background sync",
                    subtitle = "Saving your preference",
                    checked = false,
                    onCheckedChange = {},
                    busy = true,
                    leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                )
            }
            row("disabled_choice") { context ->
                SettingsChoice(
                    context = context,
                    title = "Follow system",
                    selected = true,
                    enabled = false,
                    onClick = {},
                )
            }
            row("enabled_choice") { context ->
                SettingsChoice(context = context, title = "Always", selected = false, onClick = {})
            }
        }
        SettingsGroup {
            row("disabled_switch") { context ->
                SettingsSwitch(
                    context = context,
                    title = "Notifications",
                    checked = true,
                    onCheckedChange = {},
                    enabled = false,
                    leading = { SettingsLeadingIcon(R.drawable.ic_settings_notifications) },
                )
            }
            row("disabled_link") { context ->
                SettingsLink(
                    context = context,
                    title = "Account details",
                    enabled = false,
                    onClick = {},
                    leading = { SettingsLeadingIcon(R.drawable.ic_person) },
                )
            }
        }
    }

    /** Capture the whole group so both outside corners and inter-row seams are observable. */
    private fun capture(variant: String) {
        val path = "src/test/snapshots/settings_components_$variant.png"
        composeRule.onNodeWithTag(TAG).captureRoboImage(path)
    }

    private companion object {
        const val TAG = "settings-components"
        const val SETTLE_MILLIS = 160L
    }
}
