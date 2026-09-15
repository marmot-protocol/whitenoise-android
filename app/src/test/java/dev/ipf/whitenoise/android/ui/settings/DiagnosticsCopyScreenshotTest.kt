package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The complete shared disclosure and local-only clearing remain reachable in compact windows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DiagnosticsCopyScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun storedLogsLight() = captureStored("light")

    @Test fun storedLogsAmoled() = captureStored("amoled", dark = true)

    @Test fun clearConfirmation() = captureStored("confirmation", confirmation = true)

    @Test
    fun clearConfirmationRtlLarge() = captureStored("confirmation_rtl_large", confirmation = true, largeRtl = true)

    @Test fun sharedDisclosureEnd() = capturePromptEnd("light")

    @Test fun sharedDisclosureEndRtlLarge() = capturePromptEnd("rtl_large", largeRtl = true)

    @Test
    @Config(qualifiers = "de-w360dp-h780dp-mdpi")
    fun translatedStoredLogs() = captureStored("german_large", largeRtl = true)

    private fun captureStored(
        name: String,
        dark: Boolean = false,
        confirmation: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.DECLINED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (largeRtl) 1.6f else 1f) {
                    DiagnosticsImprovementsScreen(state, onBack = {})
                }
            }
        }
        composeRule
            .onNodeWithTag("settings.list")
            .performScrollToNode(hasText(context.getString(R.string.delete_audit_logs_subtitle)))
        if (confirmation) {
            composeRule.onNodeWithText(context.getString(R.string.delete_audit_logs)).performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(R.string.diagnostics_clear_confirm_action)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/diagnostics_stored_$name.png")
        if (confirmation) {
            composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.diagnostics_clear_confirm_title)).assertDoesNotExist()
        }
    }

    private fun capturePromptEnd(
        name: String,
        largeRtl: Boolean = false,
    ) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, fontScale = if (largeRtl) 1.6f else 1f) {
                    UsageDiagnosticsPrompt(state, onDone = {})
                }
            }
        }
        composeRule
            .onNodeWithText(context.getString(R.string.diagnostics_disable_disclosure))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/diagnostics_disclosure_end_$name.png")
    }
}
