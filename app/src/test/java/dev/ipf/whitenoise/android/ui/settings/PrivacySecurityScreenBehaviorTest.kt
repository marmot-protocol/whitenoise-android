package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of Privacy & Security: switches write their preferences, lock rows follow device state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
class PrivacySecurityScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backCount = 0
    private var diagnosticsOpened = 0

    /** Screen security and Incognito keyboard write their preferences on tap. */
    @Test
    fun screenSecurityAndIncognitoWritePreferences() {
        val state = privacyState(UsageDiagnosticsDecisionFfi.DECLINED, "privacy-behaviour-switches")
        show(state, secure = false)
        composeRule.onNodeWithText("Screen security").performClick()
        composeRule.runOnIdle { assertFalse(state.allowChatScreenshotsInChats) }
        composeRule.onNodeWithText("Incognito keyboard").performClick()
        composeRule.runOnIdle { assertFalse(state.forceIncognitoKeyboard) }
    }

    /** Without a device credential the authentication switch is disabled and Android security settings are offered. */
    @Test
    fun withoutCredentialAuthenticationIsDisabledAndSecuritySettingsOffered() {
        val state = privacyState(UsageDiagnosticsDecisionFfi.DECLINED, "privacy-behaviour-insecure")
        show(state, secure = false)
        composeRule.onNodeWithText("Require device authentication").assertIsNotEnabled()
        composeRule.onNodeWithText("Set a device screen lock first.").assertExists()
        composeRule.onNodeWithText("Open Android security settings").assertExists()
        composeRule.onNodeWithText("Auto-lock").assertDoesNotExist()
    }

    /** With a credential and the lock on, Auto-lock shows the delay and its dialog writes the chosen one. */
    @Test
    fun withCredentialAutoLockChoiceWritesTheDelay() {
        val state =
            privacyAppState(
                decision = UsageDiagnosticsDecisionFfi.DECLINED,
                preferencesName = "privacy-behaviour-secure",
                seedPreferences = { putBoolean("require_app_unlock", true) },
            )
        show(state, secure = true)
        composeRule.onNodeWithText("Open Android security settings").assertDoesNotExist()
        composeRule.onNodeWithText("Immediately").assertExists()
        composeRule.onNodeWithText("Auto-lock").performClick()
        composeRule.onNodeWithText("After 5 minutes").performClick()
        composeRule.runOnIdle { assertEquals(AppLockDelay.FiveMinutes, state.appLockDelay) }
        composeRule.onNodeWithText("After 15 minutes").assertDoesNotExist()
        composeRule.onNodeWithText("After 5 minutes").assertExists()
    }

    /** The Diagnostics & Improvements row summarises the granted usage choice and opens the detail once per tap. */
    @Test
    fun diagnosticsRowSummarisesAndOpens() {
        val state = privacyState(UsageDiagnosticsDecisionFfi.GRANTED, "privacy-behaviour-granted")
        show(state, secure = false)
        composeRule.onNodeWithText("Usage").assertExists()
        composeRule.onNodeWithText("Diagnostics & Improvements").performClick()
        composeRule.runOnIdle {
            assertEquals(1, diagnosticsOpened)
            assertEquals(0, backCount)
        }
    }

    /** A declined receipt with logs off summarises as Off. */
    @Test
    fun declinedReceiptSummarisesAsOff() {
        val state = privacyState(UsageDiagnosticsDecisionFfi.DECLINED, "privacy-behaviour-declined")
        show(state, secure = false)
        composeRule.onNodeWithText("Off").assertExists()
    }

    /** The shared privacy fake on its own preference file. */
    private fun privacyState(
        decision: UsageDiagnosticsDecisionFfi,
        preferencesName: String,
    ) = privacyAppState(decision, preferencesName = preferencesName)

    /** Renders the screen in the light theme after the native privacy values are loaded. */
    private fun show(
        state: WhiteNoiseAppState,
        secure: Boolean,
    ) {
        runBlocking { state.refreshSecurityPrivacySettings() }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                DevicePrivacyScreen(
                    appState = state,
                    onBack = { backCount++ },
                    onOpenDiagnostics = { diagnosticsOpened++ },
                    credentialAvailableOverride = secure,
                )
            }
        }
    }
}
