package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DiagnosticsChoiceDetailsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun usageDetailsExpandWithoutGrantingConsent() {
        captureDetails(logs = false, dark = false)
    }

    @Test
    fun logDetailsExpandWithoutEnablingUploads() {
        captureDetails(logs = true, dark = true)
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h780dp-mdpi")
    fun logDetailsRemainScrollableWithLargeRtlText() {
        captureDetails(logs = true, dark = true, fontScale = 1.3f)
    }

    /** Exercises the disclosure saver in the restored composition, independently of dialog windows. */
    @Test
    fun expandedDetailsSurviveRecreationIndependently() {
        var changes = 0
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Column {
                    listOf("usage", "logs").forEach { kind ->
                        val title = if (kind == "usage") "Share usage" else "Share diagnostic logs"
                        DiagnosticsChoiceDetails(
                            title = title,
                            tag = "restore.$kind",
                            details = listOf(R.string.usage_diagnostics_disclosure),
                        ) {
                            ConsentSwitchRow(
                                title = title,
                                subtitle = "Summary",
                                checked = false,
                                enabled = true,
                                saving = false,
                                onCheckedChange = { changes += 1 },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Share usage, Details").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithContentDescription("Share usage, Hide details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share diagnostic logs, Details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share usage, Hide details").performClick()
        composeRule.onNodeWithContentDescription("Share usage, Details").assertIsDisplayed()
        composeRule.onAllNodes(isToggleable()).also { switches ->
            switches[0].assertIsOff()
            switches[1].assertIsOff()
        }
        assertEquals(0, changes)
    }

    private fun captureDetails(
        logs: Boolean,
        dark: Boolean,
        fontScale: Float = 1f,
    ) {
        val state = privacyAppState(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED)
        runBlocking { state.refreshSecurityPrivacySettings() }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val usageDisclosure = context.getString(R.string.usage_diagnostics_disclosure)
        val logsDisclosure = context.getString(R.string.diagnostics_group_disclosure)
        val tag = if (logs) "diagnostics.logs.details" else "diagnostics.usage.details"
        val text = if (logs) logsDisclosure else usageDisclosure
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                UsageDiagnosticsPrompt(state, onDone = {})
            }
        }
        composeRule.onNodeWithText(usageDisclosure).assertDoesNotExist()
        composeRule.onNodeWithText(logsDisclosure).assertDoesNotExist()
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.onboarding_details_collapsed),
            ),
        )
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.onboarding_details_expanded),
            ),
        )
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(if (logs) usageDisclosure else logsDisclosure).assertDoesNotExist()
        val suffix = if (logs) "logs" else "usage"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/diagnostics_details_${suffix}_$fontScale.png")
        composeRule
            .onNodeWithText(context.getString(R.string.diagnostics_disable_disclosure))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.onNodeWithText(text).assertDoesNotExist()
        composeRule.onAllNodes(isToggleable()).also { switches ->
            switches[0].assertIsOff()
            switches[1].assertIsOff()
        }
        assertEquals(UsageDiagnosticsDecisionFfi.ACCEPTANCE_REQUIRED, state.usageDiagnosticsSettings?.decision)
        assertFalse(state.auditLogSettings?.enabled ?: true)
    }
}
