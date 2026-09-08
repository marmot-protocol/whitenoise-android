package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real UI/native/relay journey. Never runs against a personal package or a supplied real identity. */
@RunWith(AndroidJUnit4::class)
class AccountSetupEndToEndTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = (composeRule.activity.application as WhiteNoiseApplication).appState

    /** Imports a fresh disposable key, advances all harmless defaults, and opens Chats after device consent. */
    @Test
    fun disposableNsecCompletesSetupThroughVisibleButtons() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("setupE2e") == "true")
        val requiredSuffix =
            requireNotNull(arguments.getString("setupE2ePackageSuffix")) {
                "Pass .preview.pr<PR_NUMBER> or .preview.prlocal as setupE2ePackageSuffix"
            }
        check(Regex("\\.preview\\.pr(?:[1-9][0-9]*|local)").matches(requiredSuffix)) {
            "Use an isolated preview package suffix"
        }
        check(context.packageName == "dev.ipf.whitenoise.android$requiredSuffix") {
            "Use the isolated onboarding test package"
        }
        val nsec =
            requireNotNull(arguments.getString("setupTestNsec")) {
                "Generate a fresh disposable nsec for this run"
            }
        composeRule.waitUntil(30_000) { app.phase == AppPhase.Onboarding }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_login)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.nostr_nsec)).performTextInput(nsec)
        composeRule.onNodeWithText(context.getString(R.string.sign_in)).performClick()
        composeRule.waitUntil(30_000) { app.accountSetup.controller != null }
        val setup = requireNotNull(app.accountSetup.controller)
        assertNotEquals(setup.account, app.activeAccountRef)
        waitForDecision(setup, OnboardingStepFfi.SINGLE_DEVICE)
        assertNotEquals(setup.account, app.activeAccountRef)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals(setup.account, app.accountSetup.controller?.account)
        click(OnboardingActionFfi.CONTINUE_ANYWAY)
        composeRule.waitUntil(30_000) {
            app.accountSetup.controller == null && app.activeAccountRef == setup.account && app.phase == AppPhase.Ready
        }
        assertEquals(setup.account, app.activeAccountRef)
    }

    /** Waits for an idle native checkpoint and verifies the requested step is the actionable decision. */
    private fun waitForDecision(
        setup: AccountSetupController,
        step: OnboardingStepFfi,
    ) {
        composeRule.waitUntil(120_000) {
            setup.state.value.currentStep
                ?.step == step &&
                !setup.state.value.busy
        }
        assertEquals(
            step,
            setup.state.value.currentStep
                ?.step,
        )
        assertTrue(
            "The native checkpoint must expose a decision",
            setup.state.value.currentStep!!
                .actions
                .isNotEmpty(),
        )
    }

    /** Scrolls to the native action button before tapping it through the real Compose surface. */
    private fun click(action: OnboardingActionFfi) {
        composeRule.onNodeWithTag("setup-action-${action.name}").performScrollTo().performClick()
    }
}
