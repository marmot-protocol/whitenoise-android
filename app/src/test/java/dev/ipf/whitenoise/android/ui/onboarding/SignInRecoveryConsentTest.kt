package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInRecoveryConsentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var confirms = 0
    private var dismissals = 0
    private var identity = "nsec1" + "q".repeat(58)

    private fun string(res: Int): String =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getString(res)

    private fun setContent(recoveryConsentVisible: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme {
                SignInContent(
                    identity = identity,
                    busy = false,
                    errorRes = null,
                    onIdentityChange = { identity = it },
                    onErrorChange = {},
                    onBack = {},
                    onSignIn = {},
                    recoveryConsentVisible = recoveryConsentVisible,
                    onRecoveryConsentConfirm = { confirms += 1 },
                    onRecoveryConsentDismiss = { dismissals += 1 },
                )
            }
        }
    }

    @Test
    fun ordinarySignInShowsNoConsentPrompt() {
        setContent(recoveryConsentVisible = false)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).assertDoesNotExist()
    }

    @Test
    fun promptStatesTheOrphanedKeyPackageRiskBeforeAnyRecovery() {
        setContent(recoveryConsentVisible = true)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.sign_in_recovery_message)).assertExists()
        assertTrue(
            "the prompt must name the orphaned-KeyPackage risk",
            string(R.string.sign_in_recovery_message).contains("orphaned"),
        )
        assertEquals("showing the prompt must not recover on its own", 0, confirms)
    }

    @Test
    fun confirmingAcknowledgesExactlyOnce() {
        setContent(recoveryConsentVisible = true)

        composeRule.onNodeWithText(string(R.string.sign_in_recovery_confirm)).performClick()

        assertEquals(1, confirms)
        assertEquals(0, dismissals)
    }

    @Test
    fun cancellingRecoversNothingAndLeavesTheEnteredKeyIntact() {
        val entered = identity
        setContent(recoveryConsentVisible = true)

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        assertEquals("cancelling must reach no engine call", 0, confirms)
        assertEquals(1, dismissals)
        assertEquals(entered, identity)
    }
}
