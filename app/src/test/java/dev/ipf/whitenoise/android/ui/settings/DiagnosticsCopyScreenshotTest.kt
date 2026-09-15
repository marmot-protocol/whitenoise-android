package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
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

    @Test fun exportConfirmation() = captureStored("export_confirmation", export = true)

    @Test
    @Config(qualifiers = "de-w360dp-h780dp-mdpi")
    fun translatedExportConfirmation() = captureStored("export_german_large", export = true, fontScale = 1.6f)

    @Test fun clearConfirmation() = captureStored("confirmation", confirmation = true)

    @Test
    fun clearConfirmationRtlLarge() =
        captureStored(
            "confirmation_rtl_large",
            confirmation = true,
            fontScale = 1.6f,
            rtl = true,
        )

    @Test fun sharedDisclosureEnd() = capturePromptEnd("light")

    @Test fun sharedDisclosureEndRtlLarge() = capturePromptEnd("rtl_large", dark = true, fontScale = 1.6f, rtl = true)

    @Test
    @Config(qualifiers = "de-w360dp-h780dp-mdpi")
    fun translatedStoredLogs() = captureStored("german_large", fontScale = 1.6f)

    private fun captureStored(
        name: String,
        dark: Boolean = false,
        confirmation: Boolean = false,
        export: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.DECLINED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = fontScale) {
                    DiagnosticsImprovementsScreen(state, onBack = {})
                }
            }
        }
        composeRule
            .onNodeWithTag("settings.list")
            .performScrollToNode(hasText(context.getString(R.string.delete_audit_logs_subtitle)))
        composeRule.onNodeWithText(context.getString(R.string.delete_audit_logs)).assertContentDescriptionEquals(
            listOf(
                context.getString(R.string.delete_audit_logs),
                context.getString(R.string.diagnostics_storage_device),
                context.getString(R.string.delete_audit_logs_subtitle),
            ).joinToString(". "),
        )
        if (export) {
            composeRule.onNodeWithText(context.getString(R.string.export_audit_logs)).performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(R.string.export_audit_logs_confirm_body)).assertIsDisplayed()
        }
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
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
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
