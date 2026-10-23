package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Same-endpoint observations retain separate provenance and never imply successful publication. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class AccountSetupFindingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** A successful inspection remains distinct from unknown lookup and publication outcomes. */
    @Test fun contextualFindingsLight() = capture("light")

    /** AMOLED contrast preserves the informational treatment of completed inspection. */
    @Test fun contextualFindingsAmoled() = capture("amoled", dark = true)

    /** RTL and larger text preserve both the exact endpoint and the purpose of each observation. */
    @Test fun contextualFindingsLargeRtl() = capture("large_rtl", rtl = true, fontScale = 1.5f)

    /** Captures native records without deriving actions, health or capability from their shared endpoint. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val endpoint = "wss://relay.example"
        val findings =
            listOf(
                setupFinding(
                    OnboardingIssueFfi.TIMED_OUT,
                    endpoint,
                    context = OnboardingFindingContextFfi.RECORD_DISCOVERY,
                    purpose = OnboardingCheckPurposeFfi.RECORD_LOOKUP,
                ),
                setupFinding(
                    OnboardingIssueFfi.INSPECTION_COMPLETED,
                    endpoint,
                    context = OnboardingFindingContextFfi.DECLARED_ROUTE,
                    purpose = OnboardingCheckPurposeFfi.DIRECTORY_QUERY,
                    outcome = OnboardingCheckOutcomeFfi.SUCCESS,
                    observedRevision = 7uL,
                ),
                setupFinding(
                    OnboardingIssueFfi.TIMED_OUT,
                    endpoint,
                    context = OnboardingFindingContextFfi.REPAIR_PUBLICATION,
                    purpose = OnboardingCheckPurposeFfi.EVENT_PUBLICATION,
                ),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = fontScale) {
                    Surface {
                        Column(
                            Modifier.padding(Dimens.spaceMd),
                            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                        ) {
                            findings.forEach { SetupFindingContent(it) }
                        }
                    }
                }
            }
        }
        composeRule.onAllNodesWithText(endpoint).assertCountEquals(3)
        composeRule.onNodeWithText("Declared relay · Directory query · Check succeeded").assertExists()
        composeRule.onNodeWithText("Repair publication · Event publication · Result unknown").assertExists()
        composeRule.onNodeWithText("Inspection completed. This alone does not confirm relay write support.").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_finding_$name.png")
    }
}
