package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RecordingIdentityLoginCalls
import dev.ipf.whitenoise.android.state.RecoveryCall
import dev.ipf.whitenoise.android.state.signInTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The add-account sheet has no consent prompt, so it must never reach the
 * recovering login — not even for the engine state that offers recovery on the
 * sign-in screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddIdentitySheetRecoveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val nsec = "nsec1" + "q".repeat(58)

    /** Adding an identity retains the sheet and existing account when staged setup fails before acceptance. */
    @Test
    fun stagedSetupFailureDoesNotDismissOrRunLegacyRecovery() {
        val engine =
            RecordingIdentityLoginCalls(
                loginFails = { error("preflight must retain ownership") },
                beginFails = { MarmotKitException.Runtime("preflight unavailable") },
            )
        val appState = signInTestAppState(app, engine)
        val previousAccount = appState.activeAccountRef
        var dismissed = false
        composeRule.setContent {
            WhiteNoiseTheme { AddIdentitySheet(appState = appState, onDismiss = { dismissed = true }) }
        }
        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(nsec)
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(nsec), engine.setupBegins)
        assertEquals(0, engine.logins.size)
        assertEquals(emptyList<RecoveryCall>(), engine.recoveries)
        assertEquals(previousAccount, appState.activeAccountRef)
        assertEquals(false, dismissed)
        composeRule.onNodeWithText(app.getString(R.string.identity_entry_error_import_failed)).assertExists()
    }

    @Test
    fun addingAnAccountNeverRecoversAnIncompleteSetup() {
        val engine =
            RecordingIdentityLoginCalls(
                loginFails = { MarmotKitException.AccountSetupRecoveryRequired() },
            )
        val appState = signInTestAppState(app, engine)
        composeRule.setContent {
            WhiteNoiseTheme {
                AddIdentitySheet(appState = appState, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(nsec)
        composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
        composeRule.waitForIdle()

        assertEquals(1, engine.logins.size)
        assertEquals(emptyList<RecoveryCall>(), engine.recoveries)
        composeRule.onNodeWithText(app.getString(R.string.sign_in_recovery_title)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.identity_entry_error_import_failed)).assertExists()
    }
}
