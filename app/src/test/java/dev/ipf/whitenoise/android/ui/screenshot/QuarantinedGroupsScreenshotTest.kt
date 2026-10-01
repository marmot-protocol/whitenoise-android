package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.whitenoise.android.state.QuarantineRecoveryOutcome
import dev.ipf.whitenoise.android.state.QuarantinedGroupRow
import dev.ipf.whitenoise.android.state.QuarantinedGroupsUiState
import dev.ipf.whitenoise.android.ui.settings.QuarantinedGroupsContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class QuarantinedGroupsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadedLight() = capture("quarantine_loaded_light", inventory())

    @Test fun recoveringDark() =
        capture(
            "quarantine_recovering_dark",
            inventory().copy(recoveringGroup = GROUP),
            dark = true,
        )

    @Test fun loadedEmpty() = capture("quarantine_empty_light", QuarantinedGroupsUiState(loaded = true))

    @Test fun recoveryAndReloadFailure() =
        capture(
            "quarantine_recovered_reload_error",
            QuarantinedGroupsUiState(
                loaded = true,
                loadFailed = true,
                outcome = QuarantineRecoveryOutcome.Recovered,
            ),
        )

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun largeRtl() = capture("quarantine_rtl_large", inventory(), rtl = true)

    private fun inventory() =
        QuarantinedGroupsUiState(
            loaded = true,
            rows =
                listOf(
                    QuarantinedGroupRow(GROUP, AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED),
                    QuarantinedGroupRow(
                        "b".repeat(64),
                        AppGroupHydrationQuarantineReasonFfi.PENDING_COMMIT_RECOVERY_FAILED,
                    ),
                ),
        )

    private fun capture(
        name: String,
        state: QuarantinedGroupsUiState,
        dark: Boolean = false,
        rtl: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    QuarantinedGroupsContent(state, onBack = {}, onRefresh = {}, onRecover = {})
                }
            }
        }
        if (rtl) {
            compose.onNodeWithTag("quarantine.list").performScrollToNode(hasTestTag("quarantine.recover"))
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val GROUP = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
